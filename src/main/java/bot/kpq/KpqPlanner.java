package bot.kpq;

import bot.Action;
import bot.Planner;
import bot.WorldState;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>Stage 5 (the boss) is out of scope for now - {@link #plan} goes idle once it detects that map.
 */
public class KpqPlanner implements Planner {
    public enum Role { LEADER, MEMBER }

    /** Minimum gap between NPC talks - the server's own {@code BLOCK_NPC_RACE_CONDT} is 500ms. */
    private static final long NPC_TALK_COOLDOWN_MS = 700;
    /** How often the leader retries starting the instance at the recruit NPC. */
    private static final long RECRUIT_RETRY_MS = 3000;
    /** How long a bot holds one stage 2-4 combo guess before trying the next. */
    private static final long COMBO_WINDOW_MS = 2500;
    /** How far into a combo window the leader checks it with the NPC, giving positions time to land. */
    private static final long COMBO_CHECK_OFFSET_MS = 900;
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
     * After this many unsuccessful attempts on the same drop/monster, stop retrying it and move on.
     * Live testing with 3-4 bots farming the same handful of mobs found every genuinely-still-valid
     * target succeeds on the very first attempt - a second attempt on the same oid is essentially
     * always "someone else already got it" (the removal broadcast is range-limited - see
     * {@code MapleMap#broadcastMessage(Packet, Point)} - so a distant bot's copy of
     * {@link WorldState#getItemDrops()}/{@link WorldState#getMonsters()} can lag well behind reality).
     * With 3-4 bots converging on the same handful of concurrently-visible targets, retrying a stale
     * one even a few times before giving up was measured costing whole *minutes* of wall-clock time
     * across a farming run - by far the largest inefficiency found, well beyond the base drop-rate
     * limit. 1 attempt keeps the safety property (bounded, still gated by
     * {@link #ACTION_RETRY_COOLDOWN_MS}) while eliminating that cost almost entirely.
     */
    private static final int MAX_TARGET_ATTEMPTS = 1;
    /** Comfortably one-shots anything in this instance - see {@link Action.AttackMonster}'s javadoc. */
    private static final int ONE_SHOT_DAMAGE = 999_999;

    private final Role role;
    /** 0 = leader, 1.. = members, in the fixed order used to assign stage 2-4 rectangle slots. */
    private final int ordinal;
    /** Leader only: exact character names to invite. */
    private final List<String> inviteNames;
    /** Leader only: passes required at stage 1 (party size minus the leader). */
    private final int passesNeeded;

    private enum Phase { PARTY_FORM, PARTY_WAIT_START, IN_STAGE1, IN_STAGE_POSITIONAL, DONE }

    private enum Stage1MemberState { NEED_QUESTION, FARMING, SUBMITTING, HANDED_OFF }

    private Phase phase = Phase.PARTY_FORM;
    private Stage1MemberState stage1State = Stage1MemberState.NEED_QUESTION;
    private int stage1TargetCoupons = -1;

    private int lastMapChangeCount = -1;
    /** -1 = not yet in a stage map (recruit map or pre-login), 0..4 = stage 1..5. */
    private int stageIndex = -1;
    private long stageEnteredAt;

    private int inviteIndex = 0;
    private long lastPartyActionAt = 0;
    private long lastNpcTalkAt = 0;
    private long lastPortalAttemptAt = 0;
    private long lastFarmActionAt = 0;
    private WorldState.NpcTalk lastHandledTalk;
    private boolean setupDoneForStage = false;
    private int lastComboAttemptIndex = -1;
    private Integer pendingAttackOid;
    private Integer pendingPickupOid;
    /** oid -> attempts so far, for whichever drop/monster this bot is currently chasing (see {@link #MAX_TARGET_ATTEMPTS}). */
    private final Map<Integer, Integer> targetAttempts = new HashMap<>();

    public KpqPlanner(Role role, int ordinal, List<String> inviteNames, int passesNeeded) {
        this.role = role;
        this.ordinal = ordinal;
        this.inviteNames = inviteNames;
        this.passesNeeded = passesNeeded;
    }

    @Override
    public Action plan(WorldState world, Point selfPosition) {
        int mapChanges = world.getMapChangeCount();
        if (mapChanges != lastMapChangeCount) {
            lastMapChangeCount = mapChanges;
            // mapChanges: 1 = just landed in the recruit map (login's own SET_FIELD), 2 = stage 1,
            // 3 = stage 2, ... 6 = stage 5. Every KPQ warp is a forward step, so this simple mapping
            // holds for the whole run - see WorldState#onMapChanged on why the actual map id isn't
            // decoded off the wire instead.
            int newStageIndex = mapChanges - 2;
            if (newStageIndex != stageIndex) {
                stageIndex = newStageIndex;
                stageEnteredAt = System.currentTimeMillis();
                lastComboAttemptIndex = -1;
                setupDoneForStage = false;
                lastHandledTalk = null;
                pendingAttackOid = null;
                pendingPickupOid = null;
                targetAttempts.clear();
                stage1State = Stage1MemberState.NEED_QUESTION;
                stage1TargetCoupons = -1;
                if (stageIndex == 0) {
                    phase = Phase.IN_STAGE1;
                } else if (stageIndex >= 1 && stageIndex <= 3) {
                    phase = Phase.IN_STAGE_POSITIONAL;
                } else if (stageIndex >= 4) {
                    phase = Phase.DONE;   // stage 5 (boss) - not automated yet
                }
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
            case IN_STAGE1 -> role == Role.LEADER ? planStage1Leader(world) : planStage1Member(world);
            case IN_STAGE_POSITIONAL -> planStagePositional(world);
            case DONE -> new Action.Idle();
        };
    }

    private Action planPartyForm(WorldState world) {
        long now = System.currentTimeMillis();
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

        long now = System.currentTimeMillis();
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

    private Action planStage1Member(WorldState world) {
        long now = System.currentTimeMillis();
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
                    yield new Action.AttackMonster(oid, ONE_SHOT_DAMAGE);
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
                List<WorldState.MonsterSighting> mobs = world.getMonsters().stream()
                        .filter(m -> m.monsterId() == KpqConstants.MOB_STAGE1)
                        .filter(m -> attemptsSoFar(m.objectId()) < MAX_TARGET_ATTEMPTS)
                        .toList();
                Optional<WorldState.MonsterSighting> mob = pickSpread(mobs);
                if (mob.isPresent()) {
                    int oid = mob.get().objectId();
                    recordAttempt(oid);
                    pendingAttackOid = oid;
                    lastFarmActionAt = now;
                    yield new Action.MoveTo(mob.get().position());
                }
                yield new Action.Idle();   // nothing alive/dropped/retryable right now
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
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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

        long elapsed = now - stageEnteredAt;
        int attemptIndex = (int) ((elapsed / COMBO_WINDOW_MS) % combos.length);
        if (attemptIndex != lastComboAttemptIndex) {
            lastComboAttemptIndex = attemptIndex;
            return new Action.MoveTo(positionFor(rects, combos[attemptIndex]));
        }

        if (role == Role.LEADER) {
            long intoWindow = elapsed % COMBO_WINDOW_MS;
            if (intoWindow >= COMBO_CHECK_OFFSET_MS && now - lastNpcTalkAt >= NPC_TALK_COOLDOWN_MS) {
                Optional<WorldState.NpcSighting> npc = findNpc(world, KpqConstants.NPC_STAGE);
                if (npc.isPresent()) {
                    lastNpcTalkAt = now;
                    return new Action.TalkToNpc(npc.get().objectId());
                }
            }
        }
        return idleOrTryPortal(now);
    }

    /**
     * {@code rectangleStages()} always needs exactly 3 players placed (verified against every combo
     * in every stage's table), regardless of party size - so only ordinals 0-2 ever stand in a
     * rectangle; ordinal 3 (this run's 4th, "surplus" bot) always parks at the stage's spawn point,
     * which is outside every rectangle by construction (it's where the map drops you in).
     */
    private Point positionFor(Rectangle[] rects, int[] combo) {
        if (ordinal < 3) {
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

    private Action idleOrTryPortal(long now) {
        if (now - lastPortalAttemptAt >= PORTAL_RETRY_MS) {
            lastPortalAttemptAt = now;
            return new Action.UsePortal(KpqConstants.NEXT_STAGE_PORTAL);
        }
        return new Action.Idle();
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
