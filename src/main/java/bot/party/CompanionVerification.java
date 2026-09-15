package bot.party;

import bot.Action;
import bot.ActionExecutor;
import bot.BotSession;
import bot.ChannelSession;
import bot.MapleConnection;
import bot.Planner;
import bot.WorldState;
import bot.combat.CombatMath;
import bot.kpq.KpqPlanner;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * Test harness, not part of the feature: live QA for summoned companions. One client plays the
 * owner - the human's side, scripted - and a second, unrelated client stands in the field map and
 * counts what the server broadcasts, so neither a companion's log nor a planner callback can make a
 * check pass. Everything printed as evidence is decoded from a server packet.
 *
 * <p>Usage: {@code CompanionVerification host port owner pass observer observerPass seconds scenario [channel]}
 * with {@code channel} 1-based (default 1), so two scenarios can run side by side on different channels.
 * <ul>
 *   <li>{@code combat}: the owner (standing in Kerning City) summons three companions, walks through
 *       {@code west00} into the field the observer is standing in, and waits there. Checks, for any
 *       owner level: companion attack broadcasts with positive, bounded damage that varies per
 *       companion; KILL_MONSTER; every companion spawning into the field after the owner (they
 *       followed through the portal). An owner of level 30+ (hurt) also needs a heal and a party buff
 *       landing on it, by skill id, seen by both clients, and its HP rising. Below 30 it needs the
 *       mixed party instead: a MAGIC_ATTACK with Energy Bolt or Magic Claw, a RANGED_ATTACK with Arrow
 *       Blow or Double Shot, and at least one critical line (a damage int with its top bit set).</li>
 *   <li>{@code kpq}: the owner (level 21-30, in Kerning City) summons three companions and then plays
 *       the leader with {@link KpqPlanner#humanLeader}, rolling its own damage from its stats. Checks:
 *       the stage NPC's clear text reaches the owner, and every stage map was entered. The observer is
 *       not used - it cannot enter the instance - so the owner's own inbound packets are the evidence.</li>
 * </ul>
 */
public final class CompanionVerification {
    private static final long LOGIN_BUDGET_MS = 45_000;
    private static final int READ_TICK_MS = 250;
    private static final long REPLAN_FLOOR_MS = 300;
    private static final int STAGE_NPC = 9020001;
    private static final int KPQ_BONUS_MAP = 103000805;
    private static final int HEAL = 2301002;
    private static final int BLESS = 2301004;
    private static final int HYPER_BODY = 1301007;
    private static final Set<Integer> MAGICIAN_ATTACKS = Set.of(2001004, 2001005);
    private static final Set<Integer> BOWMAN_ATTACKS = Set.of(3001004, 3001005);
    private static final RandomGenerator OWNER_RNG = RandomGenerator.getDefault();
    /** Far above anything a level 30-70 companion's formula yields, far below the old 999,999,999. */
    private static final int ABSURD_DAMAGE = 5_000;

    private CompanionVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("usage: CompanionVerification host port owner pass observer observerPass seconds combat|kpq [channel]");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        long budgetMs = Long.parseLong(args[6]) * 1000L;
        String scenario = args[7].toLowerCase(Locale.ROOT);
        int channel = args.length > 8 ? Integer.parseInt(args[8]) - 1 : 0;
        long maxBudgetMs = scenario.equals("kpq") ? 32 * 60 * 1000L : 6 * 60 * 1000L;
        if (!scenario.equals("combat") && !scenario.equals("kpq")) {
            throw new IllegalArgumentException("scenario must be combat or kpq");
        }
        if (budgetMs <= 0 || budgetMs > maxBudgetMs) {
            throw new IllegalArgumentException("seconds must be between 1 and " + maxBudgetMs / 1000);
        }

        Evidence ev = new Evidence();
        Observer observer = null;
        ChannelSession owner = loginBounded(host, port, args[2], args[3], channel);
        ev.ownerId = owner.charId();
        try {
            if (scenario.equals("combat")) {
                observer = new Observer(loginBounded(host, port, args[4], args[5], channel), ev);
                observer.start();
                // OwnerHarness times a step out after 40s, so the stay in the field is several waits.
                String fieldWaits = ",wait:30".repeat((int) Math.max(1, (budgetMs / 1000 - 90) / 30));
                runOwner(owner, new OwnerHarness("shumi:summon:3,waitparty:4,wait:6,portal:west00" + fieldWaits
                        + ",exit"), budgetMs, ev, false);
                observer.stopAndJoin(3000);
            } else {
                // Through a door and back before leading: three companions join in one watchdog tick, the
                // server builds those party updates concurrently, and the owner's roster was seen stuck at
                // three members until the next update. A map change always sends a fresh full roster.
                runOwner(owner, new OwnerHarness("shumi:summon:3,waitparty:3,wait:8,portal:in01,wait:4,portal:out01,"
                        + "waitparty:4"), budgetMs, ev, true);
            }
        } finally {
            if (observer != null) {
                observer.close();
            }
            try {
                owner.connection().close();
            } catch (IOException ignored) {
            }
        }
        boolean passed = scenario.equals("combat") ? ev.combatPassed() : ev.kpqPassed();
        ev.printSummary(scenario);
        System.out.println(passed ? "VERIFICATION PASSED" : "VERIFICATION FAILED");
        System.exit(passed ? 0 : 1);
    }

    /**
     * The owner's game loop. Mirrors {@code BotSession#runGameLoop} (PLAYER_MAP_TRANSFER after every
     * SET_FIELD, replan on change or a 300ms floor) but taps each inbound packet for evidence first;
     * {@code runGameLoop} has no such hook and the feature shouldn't grow one for a test.
     */
    private static void runOwner(ChannelSession session, OwnerHarness summon, long budgetMs, Evidence ev,
                                 boolean kpq) throws IOException {
        MapleConnection conn = session.connection();
        conn.setReadTimeoutMs(READ_TICK_MS);
        WorldState world = new WorldState(session.charId());
        ActionExecutor executor = new ActionExecutor(conn, world);
        Planner active = summon;
        long started = System.currentTimeMillis();
        long deadline = started + budgetMs;
        long lastPlanAt = 0;
        int lastVersion = -1;
        long lastProgressAt = 0;
        boolean forceReplan = true;
        while (System.currentTimeMillis() < deadline && !summon.exitRequested()) {
            try {
                InPacket p = conn.receive();
                int opcode = p.readShort() & 0xFFFF;
                if (opcode == SendOpcode.SET_FIELD.getValue()) {
                    world.onSetField(p);
                    conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
                    ev.ownerEnteredMap(world.getSelfMapId());
                    if (world.getSelfStats() != null) {
                        ev.ownerLevel = world.getSelfStats().level();
                    }
                } else {
                    int mark = p.getPosition();
                    ev.ownerPacket(opcode, p);
                    p.seek(mark);
                    world.accept(opcode, p);
                    if (opcode == SendOpcode.NPC_TALK.getValue()) {
                        WorldState.NpcTalk talk = world.getLastNpcTalk();
                        if (talk != null && talk.npcId() == STAGE_NPC) {
                            ev.stageNpcText(talk.text());
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {
            }

            long now = System.currentTimeMillis();
            if (kpq && active == summon && world.getPartyMemberIds().size() >= 4 && summon.idle()) {
                List<WorldState.PartyMember> companions = world.getPartyMemberIds().stream()
                        .filter(id -> id != session.charId())
                        .map(world::getPartyMember).filter(Objects::nonNull).toList();
                List<String> members = companions.stream().map(WorldState.PartyMember::name).toList();
                // As Shumi tells a player: wait until all three have arrived before talking to Lakelis.
                boolean allHere = companions.stream().allMatch(m -> m.mapId() == world.getSelfMapId());
                if (members.size() == 3 && allHere) {
                    log("owner now leads KPQ with " + members + ", own stats " + world.getSelfStats());
                    active = KpqPlanner.humanLeader(members,
                            (w, self, oid) -> ownerAttack(w, oid));
                    ev.kpqStartedAt = now;
                }
            }
            if (kpq && active != summon && now - lastProgressAt >= 60_000) {
                lastProgressAt = now;
                String roster = world.getPartyMemberIds().stream().filter(id -> id != session.charId())
                        .map(world::getPartyMember).filter(Objects::nonNull)
                        .map(m -> m.name() + "@" + m.mapId()).toList().toString();
                log("[owner] progress: map " + world.getSelfMapId() + ", passes held "
                        + world.getEtcQuantity(4001008) + ", pass drops visible "
                        + world.getItemDrops().stream().filter(d -> d.itemId() == 4001008).count()
                        + ", monsters " + world.getMonsters().size() + ", companions " + roster
                        + ", last NPC text: " + (world.getLastNpcTalk() == null ? "-"
                        : world.getLastNpcTalk().text().substring(0, Math.min(60, world.getLastNpcTalk().text().length()))));
            }
            if (ev.clearedAt > 0) {
                // Stay a while so the companions can claim their reward from the NPC, which warps each
                // of them to the bonus map; the party roster reports where each one ends up.
                long inBonus = world.getPartyMemberIds().stream().filter(id -> id != session.charId())
                        .map(world::getPartyMember).filter(Objects::nonNull)
                        .filter(m -> m.mapId() == KPQ_BONUS_MAP).count();
                if (inBonus > ev.companionsInBonusMap) {
                    ev.companionsInBonusMap = (int) inBonus;
                    log("[owner] party roster: " + inBonus + " companion(s) on the bonus map " + KPQ_BONUS_MAP
                            + " at +" + (now - ev.clearedAt) / 1000 + "s after the clear");
                }
                if (inBonus == 3 || now - ev.clearedAt > 90_000) {
                    break;
                }
            }
            if (forceReplan || world.getChangeVersion() != lastVersion || now - lastPlanAt >= REPLAN_FLOOR_MS) {
                Action action = active.plan(world, world.getSelfPosition());
                lastVersion = world.getChangeVersion();
                lastPlanAt = now;
                forceReplan = false;
                if (!(action instanceof Action.Idle)) {
                    executor.execute(action);
                    forceReplan = true;
                }
            }
        }
    }

    /** Bounds the login phase too; BotSession's protocol reads are intentionally blocking. */
    /** The stand-in human swings like a client too: its own stats and gear against the monster's defence. */
    private static Action ownerAttack(WorldState world, int monsterObjectId) {
        WorldState.SelfStats stats = world.getSelfStats();
        return world.getMonsters().stream().filter(m -> m.objectId() == monsterObjectId).findFirst()
                .filter(m -> stats != null)
                .<Action>map(m -> new Action.AttackMonster(monsterObjectId,
                        CombatMath.basicLineDamage(stats, m.monsterId(), OWNER_RNG)))
                .orElseGet(Action.Idle::new);
    }

    private static ChannelSession loginBounded(String host, int port, String user, String pass, int channel) throws Exception {
        AtomicReference<MapleConnection> connecting = new AtomicReference<>();
        FutureTask<ChannelSession> task = new FutureTask<>(
                () -> BotSession.loginAndEnterChannel(host, port, user, pass, channel, connecting::set));
        Thread thread = new Thread(task, "companion-verification-login");
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(LOGIN_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            MapleConnection c = connecting.get();
            if (c != null) {
                c.close();
            }
            task.cancel(true);
            throw new IOException("login exceeded " + LOGIN_BUDGET_MS + "ms", e);
        }
    }

    static void log(String line) {
        System.out.println(LocalTime.now() + " " + line);
    }

    /** Stands in the combat field and records the broadcasts it receives. */
    private static final class Observer implements Runnable {
        private final ChannelSession session;
        private final Evidence ev;
        private final Thread thread;
        private volatile boolean stop;

        Observer(ChannelSession session, Evidence ev) {
            this.session = session;
            this.ev = ev;
            this.thread = new Thread(this, "companion-verification-observer");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stopAndJoin(long millis) throws InterruptedException {
            close();
            thread.join(millis);
        }

        void close() {
            stop = true;
            try {
                session.connection().close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            MapleConnection c = session.connection();
            WorldState view = new WorldState(session.charId());
            try {
                c.setReadTimeoutMs(READ_TICK_MS);
                while (!stop) {
                    try {
                        InPacket p = c.receive();
                        int opcode = p.readShort() & 0xFFFF;
                        if (opcode == SendOpcode.SET_FIELD.getValue()) {
                            view.onSetField(p);
                            c.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
                            log("[observer] on map " + view.getSelfMapId());
                        } else {
                            ev.observerPacket(opcode, p);
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (IOException e) {
                if (!stop) {
                    ev.observerFailure = e.toString();
                }
            }
        }
    }

    /** What the two clients saw. Written by the owner thread and the observer thread. */
    private static final class Evidence {
        volatile int ownerId;
        volatile int ownerLevel;
        volatile long kpqStartedAt;
        volatile long clearedAt;
        volatile int companionsInBonusMap;
        volatile String observerFailure;

        // observer
        /** actor -> every damage line its attack broadcasts carried, 0 for a miss. */
        final Map<Integer, List<Integer>> damageByActor = new ConcurrentHashMap<>();
        final Set<Integer> attackSkills = ConcurrentHashMap.newKeySet();
        /** actor -> "OPCODE skill" for every attack kind it was seen using. */
        final Map<Integer, Set<String>> attackKindsByActor = new ConcurrentHashMap<>();
        /** actor -> lines the server marked critical. */
        final Map<Integer, Integer> critsByActor = new ConcurrentHashMap<>();
        /** Projectile item ids shown in RANGED_ATTACK broadcasts. */
        final Set<Integer> projectiles = ConcurrentHashMap.newKeySet();
        volatile int observerKills;
        final Set<Integer> effectsOnOwnerSeenByObserver = ConcurrentHashMap.newKeySet();
        /** Monsters a companion's attack broadcast named as its target. */
        final Set<Integer> attackedMonsters = ConcurrentHashMap.newKeySet();
        final Map<Integer, Long> spawnedIntoField = new ConcurrentHashMap<>();
        volatile long ownerSpawnedIntoFieldAt;

        // owner
        final Set<Integer> effectsOnOwner = ConcurrentHashMap.newKeySet();
        final Set<Integer> buffsOnOwner = ConcurrentHashMap.newKeySet();
        volatile int ownerHpLow = Integer.MAX_VALUE;
        volatile int ownerHpRisesWithHeal;
        private long lastHealEffectAt;
        private long lastHpRiseAt;
        /** actor -> damage lines of the companion attacks the owner saw (inside KPQ the observer can't). */
        final Map<Integer, List<Integer>> ownerSeenDamage = new ConcurrentHashMap<>();
        final Map<Integer, Set<String>> ownerSeenKinds = new ConcurrentHashMap<>();
        final Map<Integer, Integer> ownerSeenCrits = new ConcurrentHashMap<>();
        volatile int ownerSeenKills;
        final Map<Integer, Long> ownerMapsEntered = new TreeMap<>();
        volatile boolean clearText;
        private int lastHp = -1;

        synchronized void ownerEnteredMap(int mapId) {
            long now = System.currentTimeMillis();
            ownerMapsEntered.putIfAbsent(mapId, now);
            log("[owner] SET_FIELD map " + mapId + (kpqStartedAt > 0 ? " at +" + (now - kpqStartedAt) / 1000 + "s" : ""));
        }

        void stageNpcText(String text) {
            if (!clearText && text.toLowerCase(Locale.ROOT).contains("congratulations on clearing all the stages")) {
                clearText = true;
                clearedAt = System.currentTimeMillis();
                log("[owner] NPC 9020001: " + text);
            }
        }

        void ownerPacket(int opcode, InPacket p) {
            try {
                if (opcode == SendOpcode.STAT_CHANGED.getValue()) {
                    Integer hp = readHp(p);
                    if (hp != null) {
                        onOwnerHp(hp);
                    }
                } else if (opcode == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue()) {
                    // showOwnBuffEffect: byte effectId (2 = applied by a party member's skill), int skillId
                    if (p.readByte() == 2) {
                        int skill = p.readInt();
                        effectsOnOwner.add(skill);
                        if (skill == HEAL) {
                            onHealEffect();
                        }
                        log("[owner] party skill " + skill + " applied to me");
                    }
                } else if (opcode == SendOpcode.GIVE_BUFF.getValue()) {
                    // giveBuff: long mask pair (16 bytes), then short value, int buff (skill) id, int length
                    p.skip(16 + 2);
                    int buff = p.readInt();
                    if (buffsOnOwner.add(buff)) {
                        log("[owner] GIVE_BUFF skill " + buff);
                    }
                } else if (isAttack(opcode)) {
                    Attack a = readAttack(opcode, p);
                    if (a.actor != ownerId) {
                        ownerSeenDamage.computeIfAbsent(a.actor, k -> Collections.synchronizedList(new ArrayList<>()))
                                .addAll(a.lines);
                        ownerSeenKinds.computeIfAbsent(a.actor, k -> ConcurrentHashMap.newKeySet()).add(a.kind());
                        ownerSeenCrits.merge(a.actor, a.crits, Integer::sum);
                    }
                } else if (opcode == SendOpcode.KILL_MONSTER.getValue()) {
                    p.readInt();
                    if (p.readByte() != 0) {    // a death, not a removal from view
                        ownerSeenKills++;
                    }
                }
            } catch (RuntimeException ignored) {
                // A layout this harness doesn't know; the WorldState copy still gets the packet.
            }
        }

        /**
         * The server sends the healed HP (STAT_CHANGED) and then the Heal effect, a few ms apart, so
         * a rise and a Heal effect count together when either follows the other within 1.5s.
         */
        private synchronized void onOwnerHp(int hp) {
            if (lastHp >= 0 && hp > lastHp) {
                long now = System.currentTimeMillis();
                lastHpRiseAt = now;
                boolean withHeal = now - lastHealEffectAt < 1500;
                if (withHeal) {
                    ownerHpRisesWithHeal++;
                }
                log("[owner] HP " + lastHp + " -> " + hp);
            }
            ownerHpLow = Math.min(ownerHpLow, hp);
            lastHp = hp;
        }

        private synchronized void onHealEffect() {
            long now = System.currentTimeMillis();
            if (now - lastHpRiseAt < 1500 && lastHpRiseAt > lastHealEffectAt) {
                ownerHpRisesWithHeal++;
            }
            lastHealEffectAt = now;
        }

        void observerPacket(int opcode, InPacket p) {
            try {
                if (isAttack(opcode)) {
                    Attack a = readAttack(opcode, p);
                    if (a.actor == ownerId) {
                        return;
                    }
                    attackedMonsters.add(a.target);
                    List<Integer> lines = damageByActor.computeIfAbsent(a.actor,
                            k -> Collections.synchronizedList(new ArrayList<>()));
                    Set<String> kinds = attackKindsByActor.computeIfAbsent(a.actor, k -> ConcurrentHashMap.newKeySet());
                    if (lines.size() < 6 || !kinds.contains(a.kind()) || (a.crits > 0 && critsByActor.getOrDefault(a.actor, 0) < 3)) {
                        log("[observer] " + a.kind() + " by " + a.actor + " on oid " + a.target + " lines " + a.lines
                                + (a.crits > 0 ? " (" + a.crits + " critical)" : "")
                                + (a.projectile != 0 ? " projectile " + a.projectile : ""));
                    }
                    lines.addAll(a.lines);
                    kinds.add(a.kind());
                    critsByActor.merge(a.actor, a.crits, Integer::sum);
                    if (a.projectile != 0) {
                        projectiles.add(a.projectile);
                    }
                    attackSkills.add(a.skill);
                } else if (opcode == SendOpcode.KILL_MONSTER.getValue()) {
                    // killMonster(oid, animation): 0 only removes it from view (Monster#sendDestroyData);
                    // a death is 1. Count deaths of monsters a companion was seen attacking.
                    int oid = p.readInt();
                    if (p.readByte() != 0 && attackedMonsters.contains(oid) && ++observerKills <= 3) {
                        log("[observer] KILL_MONSTER oid " + oid + " (death, attacked by a companion)");
                    }
                } else if (opcode == SendOpcode.SHOW_FOREIGN_EFFECT.getValue()) {
                    // showBuffEffect(chrId, skillId, effectId=2): int chr, byte effect, int skill
                    int chr = p.readInt();
                    int effect = p.readByte();
                    int skill = p.readInt();
                    if (chr == ownerId && effect == 2 && effectsOnOwnerSeenByObserver.add(skill)) {
                        log("[observer] SHOW_FOREIGN_EFFECT on owner " + chr + ": skill " + skill);
                    }
                } else if (opcode == SendOpcode.SPAWN_PLAYER.getValue()) {
                    int chr = p.readInt();
                    long now = System.currentTimeMillis();
                    if (chr == ownerId) {
                        ownerSpawnedIntoFieldAt = now;
                    } else if (ownerSpawnedIntoFieldAt > 0) {
                        // Only arrivals after the owner's: a companion also appears here when it logs in
                        // on this map, where its last session ended, before being warped to the owner.
                        spawnedIntoField.putIfAbsent(chr, now);
                    }
                    log("[observer] SPAWN_PLAYER " + chr + (chr == ownerId ? " (owner)" : ""));
                }
            } catch (RuntimeException ignored) {
            }
        }

        record Attack(int opcode, int actor, int skill, int target, int projectile, List<Integer> lines, int crits) {
            String kind() {
                return SendOpcode.CLOSE_RANGE_ATTACK.getValue() == opcode ? "CLOSE_RANGE_ATTACK " + skill
                        : SendOpcode.RANGED_ATTACK.getValue() == opcode ? "RANGED_ATTACK " + skill : "MAGIC_ATTACK " + skill;
            }
        }

        static boolean isAttack(int opcode) {
            return opcode == SendOpcode.CLOSE_RANGE_ATTACK.getValue() || opcode == SendOpcode.RANGED_ATTACK.getValue()
                    || opcode == SendOpcode.MAGIC_ATTACK.getValue();
        }

        /**
         * PacketCreator.addAttackBody, shared by all three attack broadcasts: int chr, byte counts, byte 0x5B,
         * byte skillLevel, [int skill], display, direction, stance, speed, byte 0x0A, int projectile, then per
         * target int oid, byte 0, one int per line. {@code parseDamage} rewrites a crit-capable job's line
         * that is above its plain ceiling as {@code damage - 2^31}, which the client draws as a critical hit:
         * the top bit is the flag and the low 31 bits the number shown.
         */
        private static Attack readAttack(int opcode, InPacket p) {
            int actor = p.readInt();
            int counts = p.readByte() & 0xFF;
            p.readByte();
            int skillLevel = p.readByte() & 0xFF;
            int skill = skillLevel > 0 ? p.readInt() : 0;
            p.skip(5);
            int projectile = p.readInt();
            if ((counts >>> 4) == 0) {
                return new Attack(opcode, actor, skill, -1, projectile, List.of(), 0);
            }
            int target = p.readInt();
            p.readByte();
            List<Integer> lines = new ArrayList<>();
            int crits = 0;
            for (int i = 0; i < (counts & 0xF); i++) {
                int line = p.readInt();
                if (line < 0) {
                    crits++;
                    line &= Integer.MAX_VALUE;
                }
                lines.add(line);
            }
            return new Attack(opcode, actor, skill, target, projectile, lines, crits);
        }

        /** PacketCreator.updatePlayerStats: bool, int mask, then each stat ascending by mask bit. */
        private static Integer readHp(InPacket p) {
            p.readByte();
            int mask = p.readInt();
            if ((mask & 0x400) == 0) {
                return null;
            }
            for (int bit = 1; bit < 0x400; bit <<= 1) {
                if ((mask & bit) == 0) {
                    continue;
                }
                if (bit == 0x1 || (bit > 0x4 && bit < 0x20)) {
                    p.skip(1);
                } else if (bit <= 0x4) {
                    p.skip(4);
                } else {
                    p.skip(2);
                }
            }
            return (int) p.readShort();
        }

        boolean combatPassed() {
            List<Integer> all = damageByActor.values().stream().flatMap(l -> List.copyOf(l).stream()).toList();
            // Every companion that landed a handful of hits rolled more than one value.
            boolean spread = damageByActor.values().stream().anyMatch(l -> spread(l).stddev() > 0)
                    && damageByActor.values().stream().filter(l -> List.copyOf(l).stream().filter(d -> d > 0).count() >= 5)
                    .allMatch(l -> spread(l).stddev() > 0);
            boolean roles;
            if (ownerLevel >= 30) {
                roles = effectsOnOwnerSeenByObserver.contains(HEAL) && ownerHpRisesWithHeal > 0
                        && (buffsOnOwner.contains(BLESS) || buffsOnOwner.contains(HYPER_BODY));
            } else {
                roles = sawKind("MAGIC_ATTACK", MAGICIAN_ATTACKS) && sawKind("RANGED_ATTACK", BOWMAN_ATTACKS)
                        && critsByActor.values().stream().mapToInt(Integer::intValue).sum() > 0;
            }
            return observerFailure == null && all.stream().anyMatch(d -> d > 0) && spread
                    && all.stream().allMatch(d -> d >= 0 && d < ABSURD_DAMAGE)
                    && observerKills > 0 && roles && spawnedIntoField.size() >= 3;
        }

        private boolean sawKind(String opcode, Set<Integer> skills) {
            return attackKindsByActor.values().stream().flatMap(Set::stream)
                    .anyMatch(k -> skills.stream().anyMatch(skill -> k.equals(opcode + " " + skill)));
        }

        record Spread(int lines, int misses, int min, int median, int max, double stddev) {
            @Override
            public String toString() {
                return String.format("%d lines, %d misses; hits min %d / median %d / max %d, stddev %.1f",
                        lines, misses, min, median, max, stddev);
            }
        }

        /** Over the hits only; misses are counted separately. */
        static Spread spread(List<Integer> lines) {
            List<Integer> copy = List.copyOf(lines);
            int[] hits = copy.stream().mapToInt(Integer::intValue).filter(d -> d > 0).sorted().toArray();
            if (hits.length == 0) {
                return new Spread(copy.size(), copy.size(), 0, 0, 0, 0);
            }
            double mean = Arrays.stream(hits).average().orElse(0);
            double sd = Math.sqrt(Arrays.stream(hits).mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0));
            return new Spread(copy.size(), copy.size() - hits.length, hits[0], hits[hits.length / 2], hits[hits.length - 1], sd);
        }

        boolean kpqPassed() {
            return clearText && ownerMapsEntered.keySet().containsAll(List.of(103000800, 103000801, 103000802,
                    103000803, 103000804));
        }

        void printSummary(String scenario) {
            log("=== evidence summary (" + scenario + ") ===");
            if (scenario.equals("combat")) {
                log("owner level " + ownerLevel);
                damageByActor.forEach((actor, lines) -> log("companion " + actor + ": " + spread(lines)
                        + "; critical lines " + critsByActor.getOrDefault(actor, 0) + "; attacks "
                        + attackKindsByActor.getOrDefault(actor, Set.of())));
                log("projectiles shown in RANGED_ATTACK: " + projectiles);
                log("attack skill ids seen: " + attackSkills + "; deaths of monsters companions attacked: " + observerKills);
                log("skills shown landing on the owner (observer): " + effectsOnOwnerSeenByObserver);
                log("skills applied to owner (owner): " + effectsOnOwner + "; GIVE_BUFF ids: " + buffsOnOwner
                        + "; lowest HP " + ownerHpLow + "; HP rises paired with a Heal effect: " + ownerHpRisesWithHeal);
                log("companions that spawned into the field: " + spawnedIntoField.keySet()
                        + (ownerSpawnedIntoFieldAt > 0 ? " (owner arrived first at " + ownerSpawnedIntoFieldAt + ")" : ""));
                if (observerFailure != null) {
                    log("observer failure: " + observerFailure);
                }
            } else {
                long base = kpqStartedAt > 0 ? kpqStartedAt : 0;
                ownerMapsEntered.forEach((map, at) -> log("entered " + map
                        + (base > 0 && at >= base ? " at +" + (at - base) / 1000 + "s" : "")));
                ownerSeenDamage.forEach((actor, lines) -> log("companion " + actor + " (seen by owner): " + spread(lines)
                        + "; critical lines " + ownerSeenCrits.getOrDefault(actor, 0) + "; attacks "
                        + ownerSeenKinds.getOrDefault(actor, Set.of())));
                log("KILL_MONSTER deaths seen by owner: " + ownerSeenKills);
                log("skills applied to owner: " + effectsOnOwner + "; GIVE_BUFF ids: " + buffsOnOwner);
                log("companions that reached the bonus map after claiming their reward: " + companionsInBonusMap + " of 3");
                log("clear text received: " + clearText + (clearedAt > 0 && base > 0
                        ? " after " + (clearedAt - base) / 1000 + "s from the leader taking over" : ""));
                Long entered = ownerMapsEntered.get(103000800);
                if (clearedAt > 0 && entered != null) {
                    long inside = clearedAt - entered;
                    log(String.format("instance entry to clear: %dm%02ds; the event timer (30 min, started at entry) had %dm%02ds left",
                            inside / 60000, inside / 1000 % 60, (1_800_000 - inside) / 60000, (1_800_000 - inside) / 1000 % 60));
                }
            }
        }
    }
}
