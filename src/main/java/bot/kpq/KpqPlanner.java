package bot.kpq;

import bot.Action;
import bot.Planner;
import bot.WorldState;
import bot.combat.AttackReach;
import bot.pq.PartyQuestSession;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Drives one bot through Kerning Party Quest end to end: party formation, then whichever of the
 * five stages it reaches. One instance is one bot's whole KPQ run - see {@code KpqBot} for the CLI
 * entry point that wires this up to {@code BotSession}'s login + game loop.
 *
 * <p>The plan is entirely mechanical (per {@link WorldState} snapshots and elapsed wall-clock time),
 * matching every other {@link Planner} in this package - no LLM involvement. Coordination between the
 * separate bot processes (one JVM per character, same as {@code BotSession}/{@code Spectator}) is
 * deliberately server-mediated rather than via any side channel: the leader's actions (inviting,
 * starting the instance, talking to the stage NPC) are visible to every bot through ordinary packets
 * (party roster updates, {@code SET_FIELD} map changes), and stage 2-4 positioning is agreed on
 * without any inter-bot signal at all - every bot independently derives the same rectangle assignment
 * from ({@code stageIndex}, attempt index, its own fixed {@code ordinal}), and every bot (not just the
 * leader) periodically retries the stage-advance portal, which is safe to attempt before it's actually
 * open (see {@link Action.UsePortal}).
 *
 * <p>Combat is supplied by an {@link AttackProvider}; this planner only selects targets and never
 * assumes a damage value. Summoned companions attack with their character's real damage
 * ({@code bot.combat.CombatController}); the standalone {@code KpqBot} chooses its own.
 *
 * <p>Two layouts. Pure-bot: a bot leader plus members, ordinals 0..3, the proven configuration.
 * Human-led ({@link #summonedMember}/{@link #humanLeader}): a person leads and three summoned
 * companions take ordinals 0..2, so the three puzzle positions are always companions and the leader
 * stays outside them; combo windows are longer so a person can check with the NPC in time.
 */
public class KpqPlanner implements PartyQuestSession {
    public enum Role { LEADER, MEMBER }

    /** Minimum gap between NPC talks - the server's own {@code BLOCK_NPC_RACE_CONDT} is 500ms. */
    private static final long NPC_TALK_COOLDOWN_MS = 700;
    /** How often the leader retries starting the instance at the recruit NPC. */
    private static final long RECRUIT_RETRY_MS = 3000;
    /** How long a bot holds one stage 2-4 combo guess before trying the next. */
    private static final long COMBO_WINDOW_MS = 2500;
    /** How far into a combo window the leader checks it with the NPC, giving positions time to land. */
    private static final long COMBO_CHECK_OFFSET_MS = 900;
    private static final long HUMAN_COMBO_WINDOW_MS = 10_000;
    private static final long HUMAN_COMBO_CHECK_OFFSET_MS = 3_000;
    /** How often a bot (re)tries the next-stage portal while waiting for the stage to actually clear. */
    private static final long PORTAL_RETRY_MS = 1500;
    /**
     * Minimum gap between combat/loot actions (move-to-attack, attack, move-to-pickup, pickup). This
     * is the important one: unlike every other action in this class, a failed attack or pickup gets
     * no rejection text to react to - {@code Character#pickupItem}'s failure paths do send an
     * {@code enableActions} reply, and that reply arriving is enough for the driver loop
     * ({@code BotSession#runGameLoop}) to treat it as "something happened" and replan immediately
     * (a real move/talk/action always sets {@code forceReplan}, deliberately, so a scripted sequence
     * doesn't wait out the periodic floor between steps) - without a cooldown here, a persistently
     * failing pickup (e.g. two bots racing the same single drop) turns into an unthrottled
     * request/reply loop bounded only by round-trip time, not by anything this bot intends. Learned
     * live: an early run without this cooldown produced on the order of a thousand PickupItem
     * attempts per second per bot before it was caught and killed.
     */
    private static final long ACTION_RETRY_COOLDOWN_MS = 800;
    /**
     * After this many unsuccessful attempts on the same drop, stop retrying it and move on.
     * Live testing with 3-4 bots farming the same handful of mobs found every genuinely-still-valid
     * target succeeds on the very first attempt - a second attempt on the same oid is essentially
     * always "someone else already got it" (the removal broadcast is range-limited - see
     * {@code MapleMap#broadcastMessage(Packet, Point)} - so a distant bot's copy of
     * {@link WorldState#getItemDrops()} can lag well behind reality).
     * With 3-4 bots converging on the same handful of concurrently-visible targets, retrying a stale
     * one even a few times before giving up was measured costing whole *minutes* of wall-clock time
     * across a farming run - by far the largest inefficiency found, well beyond the base drop-rate
     * limit. 1 attempt keeps the safety property (bounded, still gated by
     * {@link #ACTION_RETRY_COOLDOWN_MS}) while eliminating that cost almost entirely.
     */
    private static final int MAX_TARGET_ATTEMPTS = 1;
    /**
     * Monsters are different: with real damage one takes many swings, so the planner stays on it (see
     * {@link #stickyMonster}). This only bounds a target that never dies from this bot's point of view,
     * such as a missed KILL_MONSTER. It has to be generous: King Slime has 8,000 HP and 160 weapon
     * defence, so a level-30 spearman's swings - misses and 1-damage lines included - average about 20,
     * and one bot alone needs ~400. At 80, seen live, every bot gave up on the stage 5 bosses.
     */
    private static final int MAX_HITS_PER_MONSTER = 600;
    /** Stage 5 members retry the reward talk this often until the leader has cleared the stage. */
    private static final long REWARD_TALK_RETRY_MS = 3000;

    /** Supplies one attack on a monster; this planner paces the calls with its own cooldown. */
    @FunctionalInterface
    public interface AttackProvider {
        Action attack(WorldState world, Point selfPosition, int monsterObjectId);
    }

    private final Role role;
    /** 0 = leader, 1.. = members, in the fixed order used to assign stage 2-4 rectangle slots. */
    private final int ordinal;
    private final int coordinationOwnerId;
    /** When true, the real human leader is parked outside the three puzzle rectangles. */
    private final boolean humanLeaderLayout;
    private final AttackProvider attackProvider;
    /** How close this bot attacks from, {@link AttackReach#MELEE} unless a summoned member's job says otherwise. */
    private final IntSupplier attackReach;
    private final LongSupplier clock;
    /** Leader only: exact character names to invite. */
    private final List<String> inviteNames;
    /** Leader only: passes required at stage 1 (party size minus the leader). */
    private final int passesNeeded;

    private enum Phase { PARTY_FORM, PARTY_WAIT_START, IN_STAGE1, IN_STAGE_POSITIONAL, IN_STAGE5, DONE }

    private enum Stage1MemberState { NEED_QUESTION, FARMING, SUBMITTING, HANDED_OFF }

    private Phase phase = Phase.PARTY_FORM;
    private Stage1MemberState stage1State = Stage1MemberState.NEED_QUESTION;
    private int stage1TargetCoupons = -1;

    /** -1 = not yet in a stage map (recruit map or pre-login), 0..4 = stage 1..5. */
    private int stageIndex = -1;
    private int lastObservedMapId = -1;

    private int inviteIndex = 0;
    private long lastPartyActionAt = 0;
    private long lastNpcTalkAt = 0;
    private long lastPortalAttemptAt = 0;
    private long lastFarmActionAt = 0;
    private WorldState.NpcTalk lastHandledTalk;
    private boolean setupDoneForStage = false;
    private int lastComboAttemptIndex = -1;
    private long lastAnnouncementWindow = Long.MIN_VALUE;
    private Integer pendingAttackOid;
    private Integer pendingPickupOid;
    /** The monster this bot is currently fighting, -1 for none; see {@link #stickyMonster}. */
    private int currentMonsterOid = -1;
    /** oid -> attempts so far, for whichever drop/monster this bot is currently chasing (see {@link #MAX_TARGET_ATTEMPTS}). */
    private final Map<Integer, Integer> targetAttempts = new HashMap<>();
    /**
     * Set once the leader sees stage 1's "you gathered up N passes" success text. Needed because
     * clearing stage 1 consumes the leader's passes (gainItem(4001008, -numpasses) server-side), so
     * {@code have < passesNeeded} becomes true again immediately after a genuine clear - identical to
     * the "still waiting on members" state it started in. Without this flag {@link #planStage1Leader}
     * would fall straight back into "wait for more passes" instead of approaching the now-open portal.
     */
    private boolean stage1Cleared = false;
    /** Leader, stages 2-4: the NPC has said this stage's portal is open. */
    private boolean positionalStageOpen = false;
    /** The NPC talk already on record when this stage was entered, so an old answer isn't read as new. */
    private WorldState.NpcTalk talkAtStageEntry;
    /** True once {@link #idleOrTryPortal} has sent this attempt's approach {@code MoveTo}; see there. */
    private boolean portalApproached = false;
    /**
     * Set once the leader sees stage 5's clear text. Same reasoning as {@link #stage1Cleared}:
     * clearing consumes the leader's 10 passes (gainItem(4001008, -10) server-side), so
     * {@code have < STAGE5_PASSES_NEEDED} would look identical to "not there yet" afterward. Stage 5
     * has no portal to approach once cleared - {@code clearPQ()} is the actual end of the whole
     * instance - so this just stops the leader from doing anything further, not from re-farming.
     */
    private boolean pqCleared = false;
    /**
     * Stage 5 member: the NPC has offered this member's reward (its "Incredible!" text, which
     * {@code 9020001.js} only shows once {@code 5stageclear} is set), and the reply claims it and
     * warps to the bonus map. Talking any earlier just gets the "welcome to the 5th stage" text.
     */
    private boolean stage5RewardOffered = false;

    /** Pure-bot layout, as run by {@code KpqBot}. */
    public KpqPlanner(Role role, int ordinal, List<String> inviteNames, int passesNeeded,
                      AttackProvider attackProvider) {
        this(role, ordinal, -1, inviteNames, passesNeeded, false, attackProvider, () -> AttackReach.MELEE,
                System::currentTimeMillis);
    }

    KpqPlanner(Role role, int ordinal, int coordinationOwnerId, List<String> inviteNames,
                       int passesNeeded, boolean humanLeaderLayout, AttackProvider attackProvider,
                       IntSupplier attackReach, LongSupplier clock) {
        if (ordinal < 0 || ordinal > 3) {
            throw new IllegalArgumentException("ordinal must be between 0 and 3");
        }
        this.role = role;
        this.ordinal = ordinal;
        this.coordinationOwnerId = coordinationOwnerId;
        this.inviteNames = List.copyOf(inviteNames);
        this.passesNeeded = passesNeeded;
        this.humanLeaderLayout = humanLeaderLayout;
        this.attackProvider = Objects.requireNonNull(attackProvider, "attackProvider");
        this.attackReach = Objects.requireNonNull(attackReach, "attackReach");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates the planner used by a summoned member after its normal follow/travel planner hands
     * over. Summoned ordinals are deliberately 0..2: the human leader owns the outside slot.
     *
     * @param attackReach read on every combat step, so a magician or bowman stands off at its reach
     */
    public static KpqPlanner summonedMember(int ordinal, int ownerId, String ownerName,
                                            AttackProvider attackProvider, IntSupplier attackReach) {
        if (ordinal < 0 || ordinal > 2) {
            throw new IllegalArgumentException("summoned member ordinal must be 0..2");
        }
        return new KpqPlanner(Role.MEMBER, ordinal, ownerId, List.of(ownerName), 0, true, attackProvider,
                attackReach, System::currentTimeMillis);
    }

    /**
     * The human leader's side of that layout. Nothing in the feature runs this - a person plays it -
     * but the verification harness does, to stand in for that person.
     */
    public static KpqPlanner humanLeader(List<String> memberNames, AttackProvider attackProvider) {
        return new KpqPlanner(Role.LEADER, 3, -1, memberNames, memberNames.size(), true, attackProvider,
                () -> AttackReach.MELEE, System::currentTimeMillis);
    }

    @Override
    public Action plan(WorldState world, Point selfPosition) {
        int mapId = world.getSelfMapId();
        if (mapId > 0 && mapId != lastObservedMapId) {
            lastObservedMapId = mapId;
            int newStageIndex = stageIndexForMap(mapId);
            if (mapId == KpqConstants.MAP_BONUS) {
                phase = Phase.DONE;
                stageIndex = -1;
            } else if (newStageIndex == -1) {
                if (stageIndex >= 0 || phase == Phase.DONE) {
                    resetRun();
                }
            } else if (newStageIndex != stageIndex) {
                enterStage(newStageIndex);
                talkAtStageEntry = world.getLastNpcTalk();
            }
        }

        // A pending invite is handled on sight regardless of phase - useful both for the normal
        // "member waiting in the recruit map" case and as a safety net if one arrives late.
        WorldState.PartyInvite invite = world.getPendingPartyInvite();
        if (invite != null) {
            world.clearPendingPartyInvite();
            return new Action.AcceptPartyInvite(invite.partyId());
        }

        return switch (phase) {
            case PARTY_FORM -> planPartyForm(world);
            case PARTY_WAIT_START -> planPartyWaitStart(world);
            case IN_STAGE1 -> role == Role.LEADER ? planStage1Leader(world) : planStage1Member(world, selfPosition);
            case IN_STAGE_POSITIONAL -> planStagePositional(world);
            case IN_STAGE5 -> planStage5(world, selfPosition);
            case DONE -> new Action.Idle();
        };
    }

    private Action planPartyForm(WorldState world) {
        long now = clock.getAsLong();
        if (role != Role.LEADER) {
            if (world.getPartyId() != -1) {
                phase = Phase.PARTY_WAIT_START;
            }
            return new Action.Idle();
        }

        if (world.getPartyId() == -1) {
            if (now - lastPartyActionAt < 1000) {
                return new Action.Idle();
            }
            lastPartyActionAt = now;
            return new Action.CreateParty();
        }

        int wantMembers = 1 + inviteNames.size();
        if (world.getPartyMemberIds().size() >= wantMembers) {
            phase = Phase.PARTY_WAIT_START;
            return new Action.Idle();
        }
        if (inviteIndex < inviteNames.size()) {
            if (now - lastPartyActionAt < RECRUIT_RETRY_MS) {
                return new Action.Idle();
            }
            lastPartyActionAt = now;
            return new Action.InviteToParty(inviteNames.get(inviteIndex++));
        }
        return new Action.Idle();   // invited everyone, waiting on their ACCEPT_PARTY_INVITE
    }

    /** Leader only - members just wait here for the party-quest warp (see {@link #plan}). */
    private Action planPartyWaitStart(WorldState world) {
        if (role != Role.LEADER) {
            return new Action.Idle();
        }
        int wantMembers = 1 + inviteNames.size();
        if (world.getPartyMemberIds().size() < wantMembers) {
            phase = Phase.PARTY_FORM;   // a member dropped before we could start - go back and re-invite
            return new Action.Idle();
        }

        long now = clock.getAsLong();
        WorldState.NpcTalk talk = world.getLastNpcTalk();
        // msgType 4 = the "I want to participate" menu (9020000.js's sendSimple) - option 0 starts it.
        // A failure reply (msgType 0, "you cannot start this party quest yet...") is deliberately left
        // unclicked: the CM already disposed itself server-side, so simply re-talking on the next
        // RECRUIT_RETRY_MS tick opens a fresh menu with no stale state to clean up first.
        if (talk != null && talk.npcId() == KpqConstants.NPC_RECRUIT && talk.msgType() == 4
                && !talk.equals(lastHandledTalk)) {
            lastHandledTalk = talk;
            lastNpcTalkAt = now;
            return new Action.RespondToNpc(4, true, 0);
        }
        if (now - lastNpcTalkAt < RECRUIT_RETRY_MS) {
            return new Action.Idle();
        }
        Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_RECRUIT);
        if (npc.isEmpty()) {
            return new Action.Idle();
        }
        lastNpcTalkAt = now;
        return new Action.TalkToNpc(npc.get().objectId());
    }

    private Action planStage1Member(WorldState world, Point selfPosition) {
        long now = clock.getAsLong();
        return switch (stage1State) {
            case NEED_QUESTION -> {
                WorldState.NpcTalk talk = world.getLastNpcTalk();
                if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
                    lastHandledTalk = talk;
                    lastNpcTalkAt = now;
                    int target = matchCouponTarget(talk.text());
                    if (target > 0) {
                        stage1TargetCoupons = target;
                        stage1State = Stage1MemberState.FARMING;
                    } else if (world.getEtcQuantity(KpqConstants.ITEM_PASS) > 0) {
                        stage1State = Stage1MemberState.HANDED_OFF;   // already finished on a prior talk
                    }
                    yield new Action.RespondToNpc(talk.msgType(), true, null);
                }
                if (now - lastNpcTalkAt < NPC_TALK_COOLDOWN_MS) {
                    yield new Action.Idle();
                }
                Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                if (npc.isEmpty()) {
                    yield new Action.Idle();
                }
                lastNpcTalkAt = now;
                yield new Action.TalkToNpc(npc.get().objectId());
            }
            case FARMING -> {
                int have = world.getEtcQuantity(KpqConstants.ITEM_COUPON);
                if (have == stage1TargetCoupons) {
                    stage1State = Stage1MemberState.SUBMITTING;
                    yield new Action.Idle();
                }
                // Every branch below sends a packet, so every branch is behind this one cooldown -
                // see ACTION_RETRY_COOLDOWN_MS's javadoc for why that's load-bearing, not cosmetic.
                if (now - lastFarmActionAt < ACTION_RETRY_COOLDOWN_MS) {
                    yield new Action.Idle();
                }
                if (have > stage1TargetCoupons) {
                    lastFarmActionAt = now;
                    yield new Action.DropItem(KpqConstants.ITEM_COUPON, have - stage1TargetCoupons);
                }
                if (pendingPickupOid != null) {
                    int oid = pendingPickupOid;
                    pendingPickupOid = null;
                    lastFarmActionAt = now;
                    yield new Action.PickupItem(oid);
                }
                if (pendingAttackOid != null) {
                    int oid = pendingAttackOid;
                    pendingAttackOid = null;
                    lastFarmActionAt = now;
                    yield attackProvider.attack(world, selfPosition, oid);
                }
                List<WorldState.ItemDrop> drops = world.getItemDrops().stream()
                        .filter(d -> d.itemId() == KpqConstants.ITEM_COUPON)
                        .filter(d -> attemptsSoFar(d.objectId()) < MAX_TARGET_ATTEMPTS)
                        .toList();
                Optional<WorldState.ItemDrop> drop = pickSpread(drops);
                if (drop.isPresent()) {
                    int oid = drop.get().objectId();
                    recordAttempt(oid);
                    pendingPickupOid = oid;
                    lastFarmActionAt = now;
                    yield new Action.MoveTo(drop.get().position());
                }
                // Idle when nothing is alive, dropped or retryable right now.
                yield fightMonster(world, selfPosition, now, id -> id == KpqConstants.MOB_STAGE1);
            }
            case SUBMITTING -> {
                if (world.getEtcQuantity(KpqConstants.ITEM_PASS) > 0) {
                    stage1State = Stage1MemberState.HANDED_OFF;
                    yield new Action.Idle();
                }
                WorldState.NpcTalk talk = world.getLastNpcTalk();
                if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
                    lastHandledTalk = talk;
                    lastNpcTalkAt = now;
                    yield new Action.RespondToNpc(talk.msgType(), true, null);
                }
                if (now - lastNpcTalkAt < NPC_TALK_COOLDOWN_MS) {
                    yield new Action.Idle();
                }
                Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                if (npc.isEmpty()) {
                    yield new Action.Idle();
                }
                lastNpcTalkAt = now;
                yield new Action.TalkToNpc(npc.get().objectId());
            }
            case HANDED_OFF -> {
                // Gated like FARMING: the drop request's own confirmation (an INVENTORY_OPERATION
                // update) takes a round trip to arrive, so without this cooldown a burst of replans
                // in that window would each see the pass as "still held" and resend the drop.
                if (world.getEtcQuantity(KpqConstants.ITEM_PASS) > 0) {
                    if (now - lastFarmActionAt < ACTION_RETRY_COOLDOWN_MS) {
                        yield new Action.Idle();
                    }
                    lastFarmActionAt = now;
                    yield new Action.DropItem(KpqConstants.ITEM_PASS, 1);
                }
                yield idleOrTryPortal(now);
            }
        };
    }

    private Action planStage1Leader(WorldState world) {
        long now = clock.getAsLong();
        // Once cleared, stay cleared - see stage1Cleared's javadoc for why have alone can't tell
        // "already cleared" apart from "still waiting on the first pass".
        if (stage1Cleared) {
            return idleOrTryPortal(now);
        }
        int have = world.getEtcQuantity(KpqConstants.ITEM_PASS);
        if (have < passesNeeded) {
            // See ACTION_RETRY_COOLDOWN_MS's javadoc - a failed pickup gets no rejection text to key
            // off, so this cooldown is the only thing standing between a stuck pickup and a flood.
            if (now - lastFarmActionAt < ACTION_RETRY_COOLDOWN_MS) {
                return new Action.Idle();
            }
            if (pendingPickupOid != null) {
                int oid = pendingPickupOid;
                pendingPickupOid = null;
                lastFarmActionAt = now;
                return new Action.PickupItem(oid);
            }
            List<WorldState.ItemDrop> drops = world.getItemDrops().stream()
                    .filter(d -> d.itemId() == KpqConstants.ITEM_PASS)
                    .filter(d -> attemptsSoFar(d.objectId()) < MAX_TARGET_ATTEMPTS)
                    .toList();
            Optional<WorldState.ItemDrop> drop = pickSpread(drops);
            if (drop.isPresent()) {
                int oid = drop.get().objectId();
                recordAttempt(oid);
                pendingPickupOid = oid;
                lastFarmActionAt = now;
                return new Action.MoveTo(drop.get().position());
            }
            return new Action.Idle();   // waiting on members to farm/hand off their passes
        }

        WorldState.NpcTalk talk = world.getLastNpcTalk();
        if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
            lastHandledTalk = talk;
            lastNpcTalkAt = now;
            if (talk.text().contains("gathered up")) {
                stage1Cleared = true;   // "You gathered up N passes! Congratulations..."
            }
            return new Action.RespondToNpc(talk.msgType(), true, null);
        }
        if (now - lastNpcTalkAt < NPC_TALK_COOLDOWN_MS) {
            return idleOrTryPortal(now);
        }
        Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
        if (npc.isEmpty()) {
            return idleOrTryPortal(now);
        }
        lastNpcTalkAt = now;
        return new Action.TalkToNpc(npc.get().objectId());
    }

    /**
     * Stage 2-4: brute-forces {@code rectangleStages()}'s hidden combo by cycling every candidate on
     * a fixed wall-clock window, positioning deterministically from ({@code stageIndex}, attempt
     * index, {@code ordinal}) alone - see the class javadoc on why that needs no message between bots.
     */
    private Action planStagePositional(WorldState world) {
        long now = clock.getAsLong();
        Rectangle[] rects = KpqConstants.rectsForStage(stageIndex);
        int[][] combos = KpqConstants.combosForStage(stageIndex);

        if (role == Role.LEADER && !setupDoneForStage) {
            WorldState.NpcTalk talk = world.getLastNpcTalk();
            if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
                lastHandledTalk = talk;
                setupDoneForStage = true;   // either got the instructions or it's already past setup
                lastNpcTalkAt = now;
                return new Action.RespondToNpc(talk.msgType(), true, null);
            }
            if (now - lastNpcTalkAt < NPC_TALK_COOLDOWN_MS) {
                return new Action.Idle();
            }
            Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
            if (npc.isEmpty()) {
                return new Action.Idle();
            }
            lastNpcTalkAt = now;
            return new Action.TalkToNpc(npc.get().objectId());
        }

        // Indexed off the absolute wall clock, not each bot's own stageEnteredAt - see this method's
        // javadoc update below. Every bot process reads the same OS clock (one Docker host), so this
        // keeps every bot testing the *same* combo at the *same* real instant without needing any
        // message between them; stageEnteredAt-relative timing let different bots drift out of phase
        // with each other by however long each one's own portal retry cycle happened to take to
        // actually walk through the stage-clear portal (PORTAL_RETRY_MS-gated, fully independent per
        // bot) - found live: that drift reached multiple seconds, more than a whole COMBO_WINDOW_MS,
        // so the correct combo could go untested by all three bots at once for a long stretch of
        // attempts purely from clock skew, not from the combo actually being wrong.
        // Head for the portal only once the stage is known to be open, and until then stay put. The
        // portal approach used to run between checks as well, and walking to the portal pulls a bot
        // off its rectangle (stage 3's portal is ~650px from the rectangles), so whether a combo was
        // tested with everyone in place came down to how the 1.5s portal cycle happened to line up
        // with the 2.5s combo window. Seen live: a pure-bot party failing stage 3 for 11 minutes.
        if (positionalStageOpen(world)) {
            return idleOrTryPortal(now);
        }

        long windowMs = humanLeaderLayout ? HUMAN_COMBO_WINDOW_MS : COMBO_WINDOW_MS;
        int attemptIndex = (int) ((now / windowMs) % combos.length);
        if (attemptIndex != lastComboAttemptIndex) {
            lastComboAttemptIndex = attemptIndex;
            return new Action.MoveTo(positionFor(rects, combos[attemptIndex]));
        }

        if (humanLeaderLayout && role == Role.MEMBER && ordinal == 0) {
            long window = now / windowMs;
            if (window != lastAnnouncementWindow && now % windowMs >= 1_000) {
                lastAnnouncementWindow = window;
                return new Action.Say("Positions ready - check with Cloto now");
            }
        }

        if (role == Role.LEADER) {
            long intoWindow = now % windowMs;
            long checkOffset = humanLeaderLayout ? HUMAN_COMBO_CHECK_OFFSET_MS : COMBO_CHECK_OFFSET_MS;
            if (intoWindow >= checkOffset && now - lastNpcTalkAt >= NPC_TALK_COOLDOWN_MS) {
                Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                if (npc.isPresent()) {
                    lastNpcTalkAt = now;
                    return new Action.TalkToNpc(npc.get().objectId());
                }
            }
        }
        return new Action.Idle();
    }

    /**
     * Stage 2-4 is open. The leader knows from the NPC's answer; a member only sees the leader arrive
     * on the next stage (the party roster carries every member's map), so members follow the leader
     * through rather than guessing. The leader is the human owner in the summoned layout.
     */
    private boolean positionalStageOpen(WorldState world) {
        if (role == Role.LEADER) {
            WorldState.NpcTalk talk = world.getLastNpcTalk();
            // Identity, not equals: this stage's "portal opened" text is identical to the last stage's.
            if (talk != null && talk != talkAtStageEntry && talk.npcId() == KpqConstants.NPC_STAGE
                    && talk.text().contains("the portal opened")) {
                positionalStageOpen = true;
            }
            return positionalStageOpen;
        }
        int leaderId = coordinationOwnerId > 0 ? coordinationOwnerId : world.getPartyLeaderId();
        WorldState.PartyMember leader = world.getPartyMember(leaderId);
        return leader != null && stageIndexForMap(leader.mapId()) == stageIndex + 1;
    }

    /**
     * Stage 5 (the boss): no puzzle, no question - every one of the 10 fixed boss mobs
     * ({@link KpqConstants#MOB_STAGE5}) drops a pass at ~100% (per the drop tables), and the leader
     * needs {@link KpqConstants#STAGE5_PASSES_NEEDED} of them (a {@code >=} check, unlike stage 1's
     * exact equality - see {@code AbstractPlayerInteraction#haveItem} - so no overshoot correction is
     * needed here). Every bot, leader included, farms bosses identically; a non-leader immediately
     * hands off any pass it ends up holding exactly like stage 1's HANDED_OFF, while the leader keeps
     * its own and additionally scavenges any pass dropped by someone else. There's no {@code next00}
     * on this map (confirmed against the WZ data). After the leader's clear response, members talk to
     * the NPC once more to claim their reward and receive the scripted warp to {@code 103000805}.
     */
    private Action planStage5(WorldState world, Point selfPosition) {
        long now = clock.getAsLong();

        if (role == Role.LEADER) {
            if (pqCleared) {
                return new Action.Idle();
            }
            int have = world.getEtcQuantity(KpqConstants.ITEM_PASS);
            if (have >= KpqConstants.STAGE5_PASSES_NEEDED) {
                WorldState.NpcTalk talk = world.getLastNpcTalk();
                if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
                    lastHandledTalk = talk;
                    lastNpcTalkAt = now;
                    if (talk.text().contains("the last, bonus stage")) {
                        pqCleared = true;   // "Here's the portal that leads you to the last, bonus stage..."
                    }
                    return new Action.RespondToNpc(talk.msgType(), true, null);
                }
                if (now - lastNpcTalkAt < NPC_TALK_COOLDOWN_MS) {
                    return new Action.Idle();
                }
                Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                if (npc.isEmpty()) {
                    return new Action.Idle();
                }
                lastNpcTalkAt = now;
                return new Action.TalkToNpc(npc.get().objectId());
            }
        } else {
            int have = world.getEtcQuantity(KpqConstants.ITEM_PASS);
            if (have > 0) {
                if (now - lastFarmActionAt < ACTION_RETRY_COOLDOWN_MS) {
                    return new Action.Idle();
                }
                lastFarmActionAt = now;
                return new Action.DropItem(KpqConstants.ITEM_PASS, have);
            }
            // Once the boss pool and its drops are gone, ask the NPC for the reward until the leader has
            // cleared the stage. Only its "Incredible!" text means the reward is on offer; answering
            // that one runs giveEventReward and the warp to the bonus map (9020001.js status 1).
            // "Gone" means nothing this bot would still act on: a pass whose one pickup attempt failed
            // stays on the ground (and in WorldState) for good, and must not hold the reward back.
            boolean bossesLeft = world.getMonsters().stream()
                    .anyMatch(m -> isStage5Boss(m.monsterId()) && attemptsSoFar(m.objectId()) < MAX_HITS_PER_MONSTER);
            boolean passesLeft = world.getItemDrops().stream()
                    .anyMatch(d -> d.itemId() == KpqConstants.ITEM_PASS && attemptsSoFar(d.objectId()) < MAX_TARGET_ATTEMPTS);
            if (!bossesLeft && !passesLeft) {
                WorldState.NpcTalk talk = world.getLastNpcTalk();
                if (talk != null && talk.npcId() == KpqConstants.NPC_STAGE && !talk.equals(lastHandledTalk)) {
                    lastHandledTalk = talk;
                    if (talk.text().contains("Incredible!")) {
                        stage5RewardOffered = true;
                        return new Action.RespondToNpc(talk.msgType(), true, null);
                    }
                    return new Action.Idle();   // not cleared yet; the script already disposed that talk
                }
                if (!stage5RewardOffered && now - lastNpcTalkAt >= REWARD_TALK_RETRY_MS) {
                    Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                    if (npc.isPresent()) {
                        lastNpcTalkAt = now;
                        return new Action.TalkToNpc(npc.get().objectId());
                    }
                }
            }
        }

        // Shared farming, both roles: kill a boss or pick up any pass on the ground. A member that
        // happens to pick up someone else's drop just relays it (hands it straight off next tick,
        // above) rather than losing it - harmless, and one less thing that has to reach the leader in
        // one hop given every single one of the 10 passes has to arrive there eventually.
        if (now - lastFarmActionAt < ACTION_RETRY_COOLDOWN_MS) {
            return new Action.Idle();
        }
        if (pendingPickupOid != null) {
            int oid = pendingPickupOid;
            pendingPickupOid = null;
            lastFarmActionAt = now;
            return new Action.PickupItem(oid);
        }
        if (pendingAttackOid != null) {
            int oid = pendingAttackOid;
            pendingAttackOid = null;
            lastFarmActionAt = now;
            return attackProvider.attack(world, selfPosition, oid);
        }
        List<WorldState.ItemDrop> drops = world.getItemDrops().stream()
                .filter(d -> d.itemId() == KpqConstants.ITEM_PASS)
                .filter(d -> attemptsSoFar(d.objectId()) < MAX_TARGET_ATTEMPTS)
                .toList();
        Optional<WorldState.ItemDrop> drop = pickSpread(drops);
        if (drop.isPresent()) {
            int oid = drop.get().objectId();
            recordAttempt(oid);
            pendingPickupOid = oid;
            lastFarmActionAt = now;
            return new Action.MoveTo(drop.get().position());
        }
        // Idle when no boss or drop is visible: between kills, or the pool is exhausted.
        return fightMonster(world, selfPosition, now, KpqPlanner::isStage5Boss);
    }

    /**
     * One combat step against a matching monster: attack if already within reach, otherwise step into
     * reach (onto it for melee, level with it and off to the side for spells and bows - see
     * {@link AttackReach#approach}) and attack on the next action tick. Each step is one {@link #ACTION_RETRY_COOLDOWN_MS}-gated
     * action like every other farming branch; the caller has already checked that cooldown.
     */
    private Action fightMonster(WorldState world, Point selfPosition, long now, IntPredicate monsterId) {
        Optional<WorldState.MonsterSighting> mob = stickyMonster(world, monsterId);
        if (mob.isEmpty()) {
            return new Action.Idle();
        }
        int oid = mob.get().objectId();
        recordAttempt(oid);
        lastFarmActionAt = now;
        int reach = attackReach.getAsInt();
        if (selfPosition != null && AttackReach.inReach(selfPosition, mob.get().position(), reach)) {
            return attackProvider.attack(world, selfPosition, oid);
        }
        pendingAttackOid = oid;
        return new Action.MoveTo(AttackReach.approach(selfPosition, mob.get().position(), reach));
    }

    /**
     * Keeps fighting the same monster until it is gone. Choosing afresh each tick with
     * {@link #pickSpread} jumps to another monster whenever the list changes; with a one-shot attack
     * that cost nothing, but with real damage it leaves many monsters wounded and few dead. A new
     * target is still chosen with {@code pickSpread}, so bots spread across monsters as before.
     */
    private Optional<WorldState.MonsterSighting> stickyMonster(WorldState world, IntPredicate monsterId) {
        List<WorldState.MonsterSighting> candidates = world.getMonsters().stream()
                .filter(m -> monsterId.test(m.monsterId()))
                .filter(m -> attemptsSoFar(m.objectId()) < MAX_HITS_PER_MONSTER)
                .toList();
        Optional<WorldState.MonsterSighting> current = candidates.stream()
                .filter(m -> m.objectId() == currentMonsterOid).findFirst();
        Optional<WorldState.MonsterSighting> chosen = current.isPresent() ? current : pickSpread(candidates);
        currentMonsterOid = chosen.map(WorldState.MonsterSighting::objectId).orElse(-1);
        return chosen;
    }

    private static boolean isStage5Boss(int monsterId) {
        for (int id : KpqConstants.MOB_STAGE5) {
            if (id == monsterId) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code rectangleStages()} always needs exactly 3 players placed (verified against every combo
     * in every stage's table), regardless of party size - so only ordinals 0-2 ever stand in a
     * rectangle; ordinal 3 (this run's 4th, "surplus" bot) always parks at the stage's spawn point,
     * which is outside every rectangle by construction (it's where the map drops you in).
     */
    private Point positionFor(Rectangle[] rects, int[] combo) {
        // In the summoned layout the real owner is the leader and must remain outside; members
        // intentionally receive ordinals 0..2 so all three rectangles are always occupied.
        if (!(humanLeaderLayout && role == Role.LEADER) && ordinal < 3) {
            int seen = 0;
            for (int j = 0; j < combo.length; j++) {
                if (combo[j] == 1) {
                    if (seen == ordinal) {
                        Rectangle r = rects[j];
                        return new Point(r.x + r.width / 2, r.y + r.height / 2);
                    }
                    seen++;
                }
            }
        }
        return KpqConstants.STAGE_PARK_SPOT[stageIndex];
    }

    static int stageIndexForMap(int mapId) {
        for (int i = 0; i < KpqConstants.STAGE_MAPS.length; i++) {
            if (KpqConstants.STAGE_MAPS[i] == mapId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * True between the two halves of a move-then-act step (step to a drop, then pick it up; step to
     * a monster, then swing). Anything else that moves the bot in between - a companion walking back
     * to heal its owner, say - makes the second half fail the server's range check, and a drop is
     * only tried once ({@link #MAX_TARGET_ATTEMPTS}).
     */
    public boolean midStep() {
        return pendingPickupOid != null || pendingAttackOid != null;
    }

    /**
     * Stages 2-4 are the positional puzzle: the party only clears a combo while everyone is standing
     * in their rectangle, and the coordinator's support step would walk a companion back to its owner
     * to heal or buff it. This replaces the map-range check {@code CombatController} used to carry for
     * exactly these stages, now that combat scope is the caller's decision.
     */
    @Override
    public boolean holdsPosition() {
        return phase == Phase.IN_STAGE_POSITIONAL;
    }

    /** Returns whether the map is one of KPQ's five instance stages. */
    public static boolean isStageMap(int mapId) {
        return stageIndexForMap(mapId) >= 0;
    }

    private void enterStage(int newStageIndex) {
        stageIndex = newStageIndex;
        lastComboAttemptIndex = -1;
        setupDoneForStage = false;
        lastHandledTalk = null;
        pendingAttackOid = null;
        pendingPickupOid = null;
        currentMonsterOid = -1;
        targetAttempts.clear();
        stage1State = Stage1MemberState.NEED_QUESTION;
        stage1TargetCoupons = -1;
        stage1Cleared = false;
        portalApproached = false;
        positionalStageOpen = false;
        pqCleared = false;
        stage5RewardOffered = false;
        if (stageIndex == 0) {
            phase = Phase.IN_STAGE1;
        } else if (stageIndex <= 3) {
            phase = Phase.IN_STAGE_POSITIONAL;
        } else {
            phase = Phase.IN_STAGE5;
        }
    }

    private void resetRun() {
        stageIndex = -1;
        phase = Phase.PARTY_FORM;
        inviteIndex = 0;
        lastHandledTalk = null;
        pendingAttackOid = null;
        pendingPickupOid = null;
        currentMonsterOid = -1;
        targetAttempts.clear();
        stage1State = Stage1MemberState.NEED_QUESTION;
        stage1TargetCoupons = -1;
        stage1Cleared = false;
        pqCleared = false;
        stage5RewardOffered = false;
        setupDoneForStage = false;
        lastComboAttemptIndex = -1;
        portalApproached = false;
    }

    /**
     * Tries the next-stage portal, approaching it first. {@code ChangeMapHandler} silently rejects a
     * portal use more than 632px from the portal's own position ({@code distanceSq > 400000}) - found
     * live: a whole cleared-stage party sat spamming {@code UsePortal} from wherever it happened to be
     * (mid-farming, often 1000+px away) and never advanced, because nothing had ever moved a bot to
     * the portal itself. Each attempt cycle is two cooldown-gated actions - move, then use - rather
     * than one, since {@link Action.MoveTo} and {@link Action.UsePortal} both need their own tick.
     */
    private Action idleOrTryPortal(long now) {
        if (now - lastPortalAttemptAt < PORTAL_RETRY_MS) {
            return new Action.Idle();
        }
        lastPortalAttemptAt = now;
        if (!portalApproached) {
            portalApproached = true;
            return new Action.MoveTo(KpqConstants.NEXT_PORTAL_POS[stageIndex]);
        }
        portalApproached = false;   // re-approach next cycle too, in case some other action moved us away
        return new Action.UsePortal(KpqConstants.NEXT_STAGE_PORTAL);
    }

    /**
     * Picks candidate {@code ordinal % size} rather than always the first, so that when several
     * bots see the exact same candidate list (they usually do - {@link WorldState} snapshots are
     * built from the same broadcasts) they spread across different targets instead of every one of
     * them racing for whichever one happens to be first. Doesn't eliminate collisions (fewer
     * candidates than bots still overlaps) but removes the common case for free, no coordination
     * needed - see the class javadoc on why every bot decides independently.
     */
    private <T> Optional<T> pickSpread(List<T> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ordinal % candidates.size()));
    }

    private int attemptsSoFar(int objectId) {
        return targetAttempts.getOrDefault(objectId, 0);
    }

    private void recordAttempt(int objectId) {
        targetAttempts.merge(objectId, 1, Integer::sum);
    }

    private static int matchCouponTarget(String text) {
        for (KpqConstants.Stage1Question q : KpqConstants.STAGE1_QUESTIONS) {
            if (text.contains(q.matchSubstring())) {
                return q.coupons();
            }
        }
        return -1;
    }

    private static Optional<WorldState.NpcSighting> findNpc(WorldState world, int npcId) {
        return world.getNpcs().stream().filter(n -> n.npcId() == npcId).findFirst();
    }
}
