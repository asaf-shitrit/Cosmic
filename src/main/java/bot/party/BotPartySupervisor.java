package bot.party;

import bot.MapPortals;
import bot.combat.CompanionLoadout;
import client.Character;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts, watches and stops the bots players summon through Shumi ({@code scripts/npc/1052102.js}).
 *
 * <p><b>Why inside the server JVM.</b> An NPC script runs in the server process, inside its container,
 * and cannot launch anything on the host. The {@code bot} package is already compiled into the server
 * jar, so each bot runs here as a thread that connects to the server's own login port over loopback.
 * On the wire nothing distinguishes it from a remote player, so party, map and combat code need no
 * special cases - the few things this class does on the server's side are exactly what a player's own
 * action or an event script would do: sending the party invite the owner asked for, and warping.
 *
 * <p><b>Caps.</b> {@link #MAX_BOTS_PER_OWNER} per player and {@link #MAX_BOTS_TOTAL} server-wide,
 * counted over live threads (a bot still shutting down still counts), plus a per-player summon
 * cooldown. A bot is never restarted automatically, so a bot that crashes can't turn into a login loop.
 *
 * <p><b>No orphans.</b> A watchdog checks every owner once a second against the server's own player
 * storage; the moment an owner is gone from the world, on another channel, or away (cash shop), all of
 * that owner's bots are dismissed. A bot that leaves the owner's party is dismissed too.
 *
 * <p><b>Placement.</b> A bot logs in wherever its character last was (a new one on Maple Island), so
 * the watchdog warps it to its owner once it is in the world, then invites it. After that the bot
 * follows on its own ({@link FollowPlanner}); only if it is left on another map (the owner used a
 * scroll, taxi or NPC rather than an adjacent portal) does the watchdog warp it again - see
 * {@link #catchUp} for how that avoids racing the bot's own portal use. Neither warp is done into an
 * event instance: a bot isn't registered to it.
 *
 * <p><b>Locking.</b> The registry is guarded by {@code this}, and nothing that touches game state
 * (parties, maps, the database) runs while holding it: NPC scripts call in on a client's thread, and
 * holding this lock while taking a party or map lock would invite a lock-order deadlock.
 */
public final class BotPartySupervisor {
    private static final Logger log = LoggerFactory.getLogger(BotPartySupervisor.class);

    public static final int MAX_BOTS_PER_OWNER = 3;
    public static final int MAX_BOTS_TOTAL = 9;
    private static final int PARTY_CAPACITY = 6;
    /** The login flow in {@code BotSession} only knows world 0. */
    private static final int SUPPORTED_WORLD = 0;

    private static final long SUMMON_COOLDOWN_MS = 10_000;
    private static final long WATCHDOG_PERIOD_MS = 1000;
    /** A bot not in the world by now is stuck in login; stop it. */
    private static final long LOGIN_DEADLINE_MS = 60_000;
    /** After asking a bot to stop, how long before its socket is closed out from under it. */
    private static final long STOP_GRACE_MS = 5000;
    private static final long INVITE_RETRY_MS = 5000;
    private static final int MAX_INVITES = 3;
    private static final long CATCH_UP_AFTER_MS = 8000;
    private static final long CATCH_UP_GIVE_UP_MS = 30_000;

    private static BotPartySupervisor instance;

    /** ownerId -> that owner's live bots. Guarded by {@code this}. */
    private final Map<Integer, List<SummonedBot>> botsByOwner = new HashMap<>();
    /** ownerId -> when that owner last summoned. Guarded by {@code this}. */
    private final Map<Integer, Long> lastSummonAt = new HashMap<>();
    /** bot name -> why it last stopped, for "My bots". Bounded by owners x slots. Guarded by {@code this}. */
    private final Map<String, String> lastStopReason = new HashMap<>();
    private final ScheduledExecutorService watchdog;
    private boolean shutDown;

    private BotPartySupervisor() {
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-party-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::watchdogTick, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public static synchronized BotPartySupervisor getInstance() {
        if (instance == null) {
            instance = new BotPartySupervisor();
        }
        return instance;
    }

    /** Server shutdown: dismiss every bot, without creating the supervisor if nobody ever used it. */
    public static void shutdownIfRunning() {
        BotPartySupervisor s;
        synchronized (BotPartySupervisor.class) {
            s = instance;
            instance = null;
        }
        if (s != null) {
            s.shutdown();
        }
    }

    // ---- NPC-facing API: every method returns ready-to-show dialogue text or a number ----

    /** How many bots {@code owner} may summon right now; 0 means {@link #summonBlockedReason} explains why. */
    public int summonCapacity(Character owner) {
        return evaluate(owner).capacity;
    }

    public String summonBlockedReason(Character owner) {
        return evaluate(owner).reason;
    }

    /** How many companions this owner currently has out, whoever asked for them. */
    public int companionCount(Character owner) {
        return snapshotOf(owner.getId()).size();
    }

    /**
     * Sends one companion without a player asking for it, and reports whether one actually started.
     *
     * <p>{@link #summon} answers with the line a player should be shown, so it says nothing about
     * whether the request was carried out - and a summon can be refused for reasons that are nobody's
     * mistake (a full budget, an event instance, a low-level player with no party). A caller acting on
     * its own, like {@code AmbientCompanionDirector}, needs the difference: an unstarted summon must
     * not be recorded as "this player has company now".
     */
    public boolean summonAmbient(Character owner) {
        int before = companionCount(owner);
        summon(owner, 1);
        return companionCount(owner) > before;
    }

    public String summon(Character owner, int requested) {
        Evaluation eval = evaluate(owner);
        if (eval.capacity <= 0) {
            return eval.reason;
        }
        int count = Math.max(1, Math.min(requested, eval.capacity));
        List<String> started = new ArrayList<>();
        synchronized (this) {
            if (shutDown) {
                return "I can't call anyone right now.";
            }
            // evaluate() ran outside the lock, so re-check what another summon could have changed since:
            // the cooldown (same owner, double-submitted) and both hard caps.
            if (System.currentTimeMillis() - lastSummonAt.getOrDefault(owner.getId(), 0L) < SUMMON_COOLDOWN_MS) {
                return "Give the last ones a moment to get here first.";
            }
            List<SummonedBot> mine = botsByOwner.computeIfAbsent(owner.getId(), k -> new ArrayList<>());
            for (int slot = 0; slot < MAX_BOTS_PER_OWNER && started.size() < count; slot++) {
                final int s = slot;
                if (mine.stream().anyMatch(b -> b.slot == s)) {
                    continue;
                }
                if (mine.size() >= MAX_BOTS_PER_OWNER || totalBotsLocked() >= MAX_BOTS_TOTAL) {
                    break;
                }
                String name = BotAccounts.botAccountName(owner.getId(), slot);
                SummonedBot bot = new SummonedBot(this, owner.getId(), owner.getName(), owner.getWorld(),
                        owner.getClient().getChannel(), slot, name);
                mine.add(bot);
                lastStopReason.remove(name);
                Thread t = new Thread(bot, "summoned-bot-" + name);
                t.setDaemon(true);
                try {
                    t.start();
                } catch (OutOfMemoryError e) {   // "unable to create native thread" - don't leak a phantom entry
                    mine.remove(bot);
                    log.warn("Couldn't start a thread for summoned bot {}", name, e);
                    break;
                }
                started.add(name);
            }
            if (started.isEmpty()) {
                if (mine.isEmpty()) {
                    botsByOwner.remove(owner.getId());
                }
                return "Everyone I know is already out with someone. Try again later.";
            }
            lastSummonAt.put(owner.getId(), System.currentTimeMillis());
        }
        log.info("Summoning {} bot(s) for {} on channel {}: {}", started.size(), owner.getName(),
                owner.getClient().getChannel(), started);
        return "Alright, I've sent word. " + (started.size() == 1 ? "Someone" : "A few of them")
                + " will arrive and join your party in a few seconds, and they'll follow you wherever you go on"
                + " this channel.";
    }

    public String dismiss(Character owner) {
        List<SummonedBot> mine = snapshotOf(owner.getId());
        if (mine.isEmpty()) {
            return "You don't have anyone following you right now.";
        }
        for (SummonedBot bot : mine) {
            bot.requestStop("dismissed");
        }
        log.info("Dismissing {} bot(s) for {}", mine.size(), owner.getName());
        return "Okay, I'll send them home. Thanks for looking after them!";
    }

    /** "My bots": every bot slot this player has a character for, with where it is according to the server. */
    public String describe(Character owner) {
        Map<Integer, BotCharacter> known = loadBotCharacters(owner.getId());
        List<SummonedBot> mine = snapshotOf(owner.getId());
        PlayerStorage storage = owner.getWorldServer().getPlayerStorage();

        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < MAX_BOTS_PER_OWNER; slot++) {
            String account = BotAccounts.botAccountName(owner.getId(), slot);
            if (account == null) {
                continue;
            }
            final int s = slot;
            SummonedBot active = mine.stream().filter(b -> b.slot == s).findFirst().orElse(null);
            BotCharacter character = known.get(slot);
            // The character's name, not the account's: it is what the player sees, and what the server
            // stores the bot under. The account is only how this class finds its own bots.
            String name = active != null && active.characterName != null ? active.characterName
                    : character == null ? null : character.name();
            if (name == null) {
                continue;
            }
            Character online = storage.getCharacterByName(name);
            String line;
            if (online != null && online.isLoggedinWorld()) {
                line = "level " + online.getLevel() + ", ch " + online.getClient().getChannel()
                        + ", #m" + online.getMapId() + "#";
                if (active != null) {
                    line += " - " + active.activity;
                }
                if (active != null && active.state() == SummonedBot.State.STOPPING) {
                    line += " (leaving)";
                }
            } else if (active != null) {
                line = active.state() == SummonedBot.State.STOPPING ? "leaving" : "on the way";
            } else if (character != null) {
                String reason;
                synchronized (this) {
                    reason = lastStopReason.get(account);    // keyed by the account this slot belongs to
                }
                line = "offline, last in #m" + character.map() + "#" + (reason == null ? "" : " (" + reason + ")");
            } else {
                continue;
            }
            sb.append("\r\n#b").append(name).append("#k - ").append(line);
        }
        return sb.length() == 0
                ? "You haven't summoned anyone yet."
                : "Here's who you've got:" + sb;
    }

    // ---- lifecycle ----

    void onBotExit(SummonedBot bot) {
        synchronized (this) {
            List<SummonedBot> mine = botsByOwner.get(bot.ownerId);
            if (mine != null) {
                mine.remove(bot);
                if (mine.isEmpty()) {
                    botsByOwner.remove(bot.ownerId);
                }
            }
            lastStopReason.put(bot.name, bot.stopReason != null ? bot.stopReason : "disconnected");
        }
        log.info("Summoned bot {} (owner {}) logged out: {}", bot.displayName(), bot.ownerName,
                bot.stopReason != null ? bot.stopReason : "connection ended");
    }

    private void shutdown() {
        List<SummonedBot> all;
        synchronized (this) {
            shutDown = true;
            all = new ArrayList<>();
            botsByOwner.values().forEach(all::addAll);
        }
        watchdog.shutdownNow();
        all.forEach(b -> b.requestStop("server shutting down"));
        long deadline = System.currentTimeMillis() + STOP_GRACE_MS;
        while (System.currentTimeMillis() < deadline && totalBots() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        all.forEach(SummonedBot::forceClose);
        log.info("Bot party supervisor stopped ({} bot(s) dismissed)", all.size());
    }

    private void watchdogTick() {
        try {
            List<SummonedBot> all;
            synchronized (this) {
                all = new ArrayList<>();
                botsByOwner.values().forEach(all::addAll);
            }
            long now = System.currentTimeMillis();
            for (SummonedBot bot : all) {
                try {
                    check(bot, now);
                } catch (Exception e) {
                    log.warn("Watchdog check failed for summoned bot {}", bot.name, e);
                }
            }
        } catch (Throwable t) {
            // A throw would silently cancel the scheduled task, and with it every orphan check.
            log.error("Bot party watchdog tick failed", t);
        }
    }

    private void check(SummonedBot bot, long now) {
        if (bot.stopRequested) {
            if (now - bot.stopRequestedAt > STOP_GRACE_MS) {
                bot.forceClose();
            }
            return;
        }

        PlayerStorage storage = Server.getInstance().getWorld(bot.worldId).getPlayerStorage();
        Character owner = storage.getCharacterById(bot.ownerId);
        if (owner == null || !owner.isLoggedinWorld()) {
            // A channel change also lands here first: Client#changeChannel marks the owner away from
            // the world before the new channel ever sees them.
            bot.requestStop("owner logged out or changed channel");
            return;
        }
        if (owner.getClient().getChannel() != bot.channel) {
            bot.requestStop("owner changed channel");
            return;
        }

        Character self = bot.charId < 0 ? null : storage.getCharacterById(bot.charId);
        boolean inWorld = self != null && self.isLoggedinWorld();
        if (!bot.placed && !inWorld) {
            // Covers both a login that hangs and one where the channel silently never loads the
            // character after PLAYER_LOGGEDIN (seen live: the bot sat connected with no SET_FIELD).
            // Measured from its turn at the login gate: a bot queued behind a stuck one isn't stuck itself,
            // and the stuck one is stopped (releasing the gate) by this same check.
            long loginStartedAt = bot.loginStartedAt;
            if (loginStartedAt > 0 && now - loginStartedAt > LOGIN_DEADLINE_MS) {
                log.warn("Summoned bot {} didn't enter the world within {}s, stopping it. Recent bot log:\n{}",
                        bot.name, LOGIN_DEADLINE_MS / 1000, bot.recentLogText());
                bot.requestStop("couldn't log in");
            }
            return;
        }
        if (!inWorld || self.isChangingMaps()) {
            return;             // between maps, or its logout is already under way
        }

        if (!bot.prepared) {
            // self was looked up by the id this summon's own connection logged in with, so it is the
            // bot's character, never the owner or a bystander; prepare() checks the name again.
            // Done once per summon, before CompanionPlanner is allowed to act.
            try {
                // The character's name, not the account's: prepare() refuses to touch a character that is
                // not this summon's own, and the name it is told is the one the login read back.
                bot.prepared = CompanionLoadout.prepare(self, owner, bot.slot,
                        bot.characterName != null ? bot.characterName : bot.name);
            } catch (RuntimeException e) {
                // Not retried: a failure here would otherwise log once a second for the whole session.
                log.warn("Couldn't prepare summoned bot {} for {}", bot.name, owner.getName(), e);
                bot.requestStop("couldn't prepare this companion");
            }
            return;
        }

        if (!bot.placed) {
            if (owner.getEventInstance() != null) {
                return;
            }
            // Warp only if needed. FollowPlanner doesn't use portals right after logging in, so this
            // warp can't overlap a CHANGE_MAP of the bot's own (see LOGIN_TRAVEL_DELAY_MS there).
            if (self.getMapId() != owner.getMapId()) {
                warpToOwner(self, owner);
            }
            bot.placed = true;
            return;
        }

        Party ownerParty = owner.getParty();
        Party selfParty = self.getParty();
        if (selfParty != null) {
            if (ownerParty == null || selfParty.getId() != ownerParty.getId()) {
                bot.requestStop("left your party");
                return;
            }
            if (!bot.joinedParty) {
                bot.joinedParty = true;
                log.info("Summoned bot {} joined {}'s party {}", bot.displayName(), owner.getName(), selfParty.getId());
            }
        } else if (bot.joinedParty) {
            bot.requestStop("left your party");
            return;
        } else if (now - bot.lastInviteAt >= INVITE_RETRY_MS) {
            if (bot.invitesSent >= MAX_INVITES) {
                bot.requestStop("couldn't join your party");
                return;
            }
            bot.invitesSent++;
            bot.lastInviteAt = now;
            invite(owner, self, bot);
            return;
        }

        catchUp(bot, self, owner, now);
    }

    /**
     * The human fills the leader role and all three managed companions fill the puzzle positions.
     * Check instance identity as well as party membership: two parallel PQs share numeric map ids.
     *
     * @return null when {@code member} may play its part, otherwise why not (shown in "Who's
     *         following me?" and logged, since a companion waiting here otherwise just stands still)
     */
    String kpqTeamProblem(SummonedBot member, Character self, Character owner) {
        Party party = owner.getParty();
        if (party == null || party.getLeaderId() != owner.getId()) {
            return "you aren't leading the party";
        }
        if (party.getMembers().size() != 4) {
            return "the party has " + party.getMembers().size() + " members, not 4";
        }
        if (owner.getEventInstance() == null || self.getEventInstance() != owner.getEventInstance()) {
            return "not in the same party quest as you";
        }
        List<SummonedBot> companions = snapshotOf(member.ownerId);
        if (companions.size() != 3) {
            return companions.size() + " companions with you, not 3";
        }
        PlayerStorage storage = owner.getWorldServer().getPlayerStorage();
        for (SummonedBot companion : companions) {
            Character character = storage.getCharacterById(companion.charId);
            if (companion.stopRequested || character == null) {
                return companion.name + " is leaving";
            }
            if (!companion.joinedParty || character.getParty() == null || character.getParty().getId() != party.getId()) {
                return companion.name + " isn't in your party";
            }
            if (character.getEventInstance() != owner.getEventInstance()) {
                return companion.name + " isn't in your party quest";
            }
        }
        return null;
    }

    /**
     * Warps a bot that has been left on another map. The hazard is racing the bot's own portal use:
     * packet handlers take no per-client lock, so a warp from this thread could run concurrently with
     * {@code ChangeMapHandler} for the same character. So the timer restarts whenever either side's
     * map changes, and a warp only happens once the bot can no longer be mid-portal:
     * <ul>
     *   <li>no portal leads from the bot's map to the owner's, so {@link FollowPlanner} never tried one; or</li>
     *   <li>one does, but the bot has had {@link #CATCH_UP_GIVE_UP_MS} - several times the planner's
     *       whole attempt budget - and must have given up on it (a quest-gated portal, say).</li>
     * </ul>
     */
    private static void catchUp(SummonedBot bot, Character self, Character owner, long now) {
        int selfMap = self.getMapId();
        int ownerMap = owner.getMapId();
        if (selfMap == ownerMap || owner.getEventInstance() != null) {
            bot.awayFromOwnerSince = 0;
            return;
        }
        if (bot.awayFromOwnerSince == 0 || bot.awaySelfMap != selfMap || bot.awayOwnerMap != ownerMap) {
            bot.awayFromOwnerSince = now;
            bot.awaySelfMap = selfMap;
            bot.awayOwnerMap = ownerMap;
            return;
        }
        long away = now - bot.awayFromOwnerSince;
        boolean portalExists = MapPortals.leadingTo(selfMap, ownerMap).isPresent();
        if (away > (portalExists ? CATCH_UP_GIVE_UP_MS : CATCH_UP_AFTER_MS) && warpToOwner(self, owner)) {
            log.info("Summoned bot {} fell behind {} (map {} -> {}), warped", bot.displayName(), owner.getName(), selfMap, ownerMap);
            bot.awayFromOwnerSince = 0;
        }
    }

    /**
     * The same steps {@code PartyOperationHandler} takes when a player invites someone by name - create
     * the party if the owner has none, check it has room, register the invite with
     * {@code InviteCoordinator} and send it - done on the owner's behalf, since summoning is the
     * owner asking for these bots in their party. The bot answers it over the wire like any invitee,
     * so the join itself goes through {@code PartyOperationHandler} unchanged.
     */
    private void invite(Character owner, Character self, SummonedBot bot) {
        Party party = owner.getParty();
        if (party == null) {
            if (!Party.createParty(owner, true)) {
                bot.requestStop("you couldn't start a party");
                return;
            }
            party = owner.getParty();
        }
        if (party.getMembers().size() >= PARTY_CAPACITY) {
            bot.requestStop("your party is full");
            return;
        }
        if (InviteCoordinator.createInvite(InviteType.PARTY, owner, party.getId(), self.getId())) {
            self.sendPacket(PacketCreator.partyInvite(owner));
        }
    }

    /**
     * A server-initiated warp, as an event script does - {@code SET_FIELD} reaches the bot like any warp
     * and it sends {@code PLAYER_MAP_TRANSFER} itself. Placed on the owner's spot; it walks out to its
     * follow slot from there.
     */
    private static boolean warpToOwner(Character self, Character owner) {
        MapleMap map = owner.getMap();
        if (map == null) {
            return false;
        }
        self.changeMap(map, new Point(owner.getPosition()));
        return true;
    }

    // ---- helpers ----

    private record Evaluation(int capacity, String reason) {}

    private Evaluation evaluate(Character owner) {
        if (owner.getWorld() != SUPPORTED_WORLD) {
            return new Evaluation(0, "I only know people in the first world, sorry.");
        }
        if (BotAccounts.botAccountName(owner.getId(), MAX_BOTS_PER_OWNER - 1) == null) {
            return new Evaluation(0, "Sorry, I can't find anyone for you.");
        }
        if (owner.getEventInstance() != null) {
            return new Evaluation(0, "Not while you're in the middle of something like this!");
        }
        Party party = owner.getParty();
        if (party == null && owner.getLevel() < 10) {
            return new Evaluation(0, "They'd join you in a party, and you need to be at least #blevel 10#k to"
                    + " start one.");
        }

        int partyMembers = party == null ? 1 : party.getMembers().size();
        synchronized (this) {
            if (shutDown) {
                return new Evaluation(0, "I can't call anyone right now.");
            }
            List<SummonedBot> mine = botsByOwner.getOrDefault(owner.getId(), List.of());
            long sinceLast = System.currentTimeMillis() - lastSummonAt.getOrDefault(owner.getId(), 0L);
            if (sinceLast < SUMMON_COOLDOWN_MS) {
                return new Evaluation(0, "Give the last ones a moment to get here first.");
            }
            // Bots still on their way aren't in the party yet but will take a seat.
            long pendingSeats = mine.stream().filter(b -> !b.stopRequested && !b.joinedParty).count();
            int ownerFree = MAX_BOTS_PER_OWNER - mine.size();
            int serverFree = MAX_BOTS_TOTAL - totalBotsLocked();
            int partyFree = PARTY_CAPACITY - partyMembers - (int) pendingSeats;

            if (ownerFree <= 0) {
                return new Evaluation(0, "You've already got " + MAX_BOTS_PER_OWNER + " following you. That's"
                        + " as many as I can spare for one person.");
            }
            if (serverFree <= 0) {
                return new Evaluation(0, "Everyone I know is already out with someone. Try again later.");
            }
            if (partyFree <= 0) {
                return new Evaluation(0, "Your party doesn't have room for anyone else.");
            }
            return new Evaluation(Math.min(ownerFree, Math.min(serverFree, partyFree)), "");
        }
    }

    private synchronized List<SummonedBot> snapshotOf(int ownerId) {
        return new ArrayList<>(botsByOwner.getOrDefault(ownerId, List.of()));
    }

    private synchronized int totalBots() {
        return totalBotsLocked();
    }

    private int totalBotsLocked() {
        return botsByOwner.values().stream().mapToInt(List::size).sum();
    }

    /** slot -> the companion character in that slot, for this owner's bot accounts that have one. */
    private static Map<Integer, BotCharacter> loadBotCharacters(int ownerId) {
        Map<Integer, BotCharacter> result = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT a.name, c.name, c.map FROM accounts a JOIN characters c ON c.accountid = a.id"
                             + " WHERE a.name IN ("
                             + String.join(", ", java.util.Collections.nCopies(MAX_BOTS_PER_OWNER, "?")) + ")")) {
            for (int slot = 0; slot < MAX_BOTS_PER_OWNER; slot++) {
                String account = BotAccounts.botAccountName(ownerId, slot);
                ps.setString(slot + 1, account == null ? "" : account);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = BotAccounts.slotOfBotAccount(rs.getString(1));
                    if (slot >= 0) {
                        result.put(slot, new BotCharacter(rs.getString(2), rs.getInt(3)));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Couldn't list bot characters for owner {}", ownerId, e);
        }
        return result;
    }

    /** One companion that exists in the database: what it is called, and where it was last saved. */
    private record BotCharacter(String name, int map) {}
}
