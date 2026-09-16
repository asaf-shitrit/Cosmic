package bot.party;

import bot.Action;
import bot.ActionExecutor;
import bot.BotLog;
import bot.BotSession;
import bot.ChannelSession;
import bot.MapleConnection;
import bot.Planner;
import bot.WorldState;
import bot.budget.HumanPresence;
import client.Character;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One summoned bot: a real v83 client running on its own thread inside the server JVM, connected over
 * loopback to the server's own login port. From the server's side it is an ordinary TCP player - it
 * logs in, is loaded from the database, joins a party through {@code PartyOperationHandler}, moves
 * through {@code MovePlayerHandler} and uses portals through {@code ChangeMapHandler} - so nothing about
 * parties, maps or combat is special-cased for it.
 *
 * <p>Fields read by {@link BotPartySupervisor}'s watchdog thread are volatile; the watchdog-only
 * bookkeeping at the bottom is touched by that one thread alone.
 */
final class SummonedBot implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SummonedBot.class);

    private static final String LOGIN_HOST = "127.0.0.1";
    private static final int LOGIN_PORT = 8484;
    /** Hard wall-clock ceiling on one session, independent of the orphan checks - the last line of defence. */
    private static final long MAX_SESSION_MS = 4 * 60 * 60 * 1000L;
    /** Reason 7 ("already logged in") is transient: the account's previous session is still tearing down. */
    private static final int LOGIN_ATTEMPTS = 3;
    private static final long LOGIN_RETRY_MS = 3000;
    private static final int RECENT_LOG_LINES = 30;

    /**
     * One summoned bot logs in at a time, from its first login packet until its first SET_FIELD.
     * Every summoned bot connects from 127.0.0.1, and the login-to-channel handoff keeps per-IP,
     * single-slot state: CHAR_SELECT stores the client's hwid in {@code HostHwidCache} under the remote
     * IP, and {@code PlayerLoggedinHandler} takes it back out with
     * {@code SessionCoordinator#pickLoginSessionHwid}. Two bots between those steps at once share one
     * slot, so one of them finds it empty and gets {@code c.disconnect(true, false)} on a client with
     * no player yet - which leaves the socket open and the character never loaded, so the bot just
     * waits for a SET_FIELD that never comes. Seen live twice with 3 simultaneous summons: 3 channel
     * connects, 2 characters loaded. A stuck holder can't wedge the queue for long: the watchdog stops
     * a bot that isn't in the world within its login deadline and closes its socket, which releases
     * the gate in {@link #run}'s {@code finally}.
     */
    private static final Semaphore LOGIN_GATE = new Semaphore(1, true);

    enum State { STARTING, IN_WORLD, STOPPING }

    final int ownerId;
    final String ownerName;
    final int worldId;
    /** 1-based, as the server counts channels. */
    final int channel;
    /** 0-based slot among this owner's bots; also decides where it stands beside the owner. */
    final int slot;
    final String name;

    /**
     * The name the character actually has, which is the one players see: {@link CompanionNames} gives it
     * a human name when the account has no character yet, and an existing companion keeps whatever it
     * was created with. Null until the login has read it back.
     */
    volatile String characterName;

    /** The name to show a person: the character's once it is known, the account's before that. */
    String displayName() {
        return characterName != null ? characterName : name;
    }
    final long startedAt = System.currentTimeMillis();

    private final BotPartySupervisor supervisor;

    /** When this bot got its turn at {@link #LOGIN_GATE}; 0 while still queued behind another bot. */
    volatile long loginStartedAt;
    volatile int charId = -1;
    volatile boolean stopRequested;
    volatile long stopRequestedAt;
    volatile String stopReason;
    private volatile MapleConnection connection;
    private final Deque<String> recentLog = new ArrayDeque<>();

    /** Set by the watchdog, also read by the NPC thread when counting free party seats. */
    volatile boolean joinedParty;
    volatile boolean prepared;
    volatile boolean placed;
    volatile String activity = "arriving";

    // Watchdog-thread only.
    int invitesSent;
    long lastInviteAt;
    long awayFromOwnerSince;
    int awaySelfMap;
    int awayOwnerMap;

    SummonedBot(BotPartySupervisor supervisor, int ownerId, String ownerName, int worldId, int channel, int slot,
                String name) {
        this.supervisor = supervisor;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.worldId = worldId;
        this.channel = channel;
        this.slot = slot;
        this.name = name;
    }

    State state() {
        if (stopRequested) {
            return State.STOPPING;
        }
        return charId < 0 ? State.STARTING : State.IN_WORLD;
    }

    void requestStop(String reason) {
        if (!stopRequested) {
            stopReason = reason;
            stopRequestedAt = System.currentTimeMillis();
            stopRequested = true;
        }
    }

    /**
     * Unblocks a bot that didn't wind down on its own within the grace period - typically one blocked
     * in a login read. Closing the socket makes that read throw, which ends the thread.
     */
    void forceClose() {
        MapleConnection conn = connection;
        if (conn != null) {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        BotLog.setSink(this::record);
        AtomicBoolean gateHeld = new AtomicBoolean();
        try {
            if (!acquireLoginGate()) {
                return;             // stopped while queued
            }
            gateHeld.set(true);
            loginStartedAt = System.currentTimeMillis();
            ChannelSession session = login();
            if (session == null) {
                return;             // stopped while logging in
            }
            WorldState world = new WorldState(session.charId());
            try (MapleConnection conn = session.connection()) {
                charId = session.charId();
                CompanionPlanner companion = new CompanionPlanner(this, supervisor,
                        () -> liveCharacter(charId), () -> liveCharacter(ownerId));
                Planner planner = (w, self) -> {
                    // First SET_FIELD: the channel has handled our PLAYER_LOGGEDIN, so the next bot may go.
                    if (w.getMapChangeCount() > 0 && gateHeld.compareAndSet(true, false)) {
                        LOGIN_GATE.release();
                    }
                    return companion.plan(w, self);
                };
                BotSession.runGameLoop(conn, charId, planner, MAX_SESSION_MS, () -> stopRequested, world);
                leavePartyCleanly(conn, world);
            }
        } catch (Exception e) {
            if (!stopRequested) {
                log.warn("Summoned bot {} (owner {}) crashed: {}. Recent bot log:\n{}", name, ownerName, e, recentLogText());
            }
        } finally {
            if (gateHeld.compareAndSet(true, false)) {
                LOGIN_GATE.release();
            }
            supervisor.onBotExit(this);
        }
    }

    private Character liveCharacter(int id) {
        var world = Server.getInstance().getWorld(worldId);
        Character character = world == null ? null : world.getPlayerStorage().getCharacterById(id);
        return character != null && character.isLoggedinWorld()
                && character.getClient().getChannel() == channel ? character : null;
    }

    /** Waits for {@link #LOGIN_GATE}, giving up only if this bot is stopped meanwhile. */
    private boolean acquireLoginGate() throws InterruptedException {
        while (!LOGIN_GATE.tryAcquire(250, TimeUnit.MILLISECONDS)) {
            if (stopRequested) {
                return false;
            }
        }
        return true;
    }

    private ChannelSession login() throws Exception {
        String password = BotAccounts.issueSessionPassword(name);
        // A human name for a character this account has yet to create. Best-effort: if the name lookup
        // itself fails, the account name is still a legal (if charmless) fallback.
        String preferredCharName = null;
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection()) {
            preferredCharName = CompanionNames.available(ownerId, slot, CompanionNames.databaseLookup(con));
        } catch (java.sql.SQLException e) {
            log.warn("Couldn't look up a companion name for {}; falling back to the account name", name, e);
        }
        for (int attempt = 1; ; attempt++) {
            if (stopRequested) {
                return null;
            }
            try {
                ChannelSession session = BotSession.loginAndEnterChannel(LOGIN_HOST, LOGIN_PORT, name, password,
                        channel - 1, conn -> connection = conn, preferredCharName);
                characterName = session.charName();
                // Register what it is really called, so nothing mistakes this bot for a player. The account
                // check in HumanPresence covers the moment before this line runs.
                HumanPresence.registerBot(characterName);
                return session;
            } catch (BotSession.LoginRejectedException e) {
                if (e.reason() != 7 || attempt >= LOGIN_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(LOGIN_RETRY_MS);
            }
        }
    }

    /**
     * A dismissal leaves the party before disconnecting. Without it the owner's roster keeps an
     * offline bot in a slot, and a party only holds six. If the server connection is already gone
     * there is nothing to leave cleanly; the server drops the session on its own.
     */
    private void leavePartyCleanly(MapleConnection conn, WorldState world) {
        if (world.getPartyId() == -1) {
            return;
        }
        try {
            new ActionExecutor(conn, world).execute(new Action.LeaveParty());
            // The close that follows is processed after this packet on the same connection, but give
            // the handler a moment so the leave is visible to the party before the logout is.
            Thread.sleep(300);
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private void record(String line) {
        if (line.startsWith("[WARN]") || line.startsWith("[FAIL]")) {
            log.warn("Summoned bot {}: {}", name, line);
        }
        synchronized (recentLog) {
            if (recentLog.size() == RECENT_LOG_LINES) {
                recentLog.removeFirst();
            }
            recentLog.addLast(line);
        }
    }

    String recentLogText() {
        synchronized (recentLog) {
            return String.join("\n", recentLog);
        }
    }
}
