package bot.kpq;

import bot.Action;
import bot.Planner;
import bot.WorldState;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
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
    private WorldState.NpcTalk lastHandledTalk;
    private boolean setupDoneForStage = false;
    private int lastComboAttemptIndex = -1;
    private Integer pendingAttackOid;
    private Integer pendingPickupOid;

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
                if (have > stage1TargetCoupons) {
                    yield new Action.DropItem(KpqConstants.ITEM_COUPON, have - stage1TargetCoupons);
                }
                if (have == stage1TargetCoupons) {
                    stage1State = Stage1MemberState.SUBMITTING;
                    yield new Action.Idle();
                }
                if (pendingPickupOid != null) {
                    int oid = pendingPickupOid;
                    pendingPickupOid = null;
                    yield new Action.PickupItem(oid);
                }
                if (pendingAttackOid != null) {
                    int oid = pendingAttackOid;
                    pendingAttackOid = null;
                    yield new Action.AttackMonster(oid, ONE_SHOT_DAMAGE);
                }
                Optional<WorldState.ItemDrop> drop = world.getItemDrops().stream()
                        .filter(d -> d.itemId() == KpqConstants.ITEM_COUPON).findFirst();
                if (drop.isPresent()) {
                    pendingPickupOid = drop.get().objectId();
                    yield new Action.MoveTo(drop.get().position());
                }
                Optional<WorldState.MonsterSighting> mob = world.getMonsters().stream()
                        .filter(m -> m.monsterId() == KpqConstants.MOB_STAGE1).findFirst();
                if (mob.isPresent()) {
                    pendingAttackOid = mob.get().objectId();
                    yield new Action.MoveTo(mob.get().position());
                }
                yield new Action.Idle();   // nothing alive/dropped right now - waiting on the 15s respawn
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
                if (world.getEtcQuantity(KpqConstants.ITEM_PASS) > 0) {
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
            if (pendingPickupOid != null) {
                int oid = pendingPickupOid;
                pendingPickupOid = null;
                return new Action.PickupItem(oid);
            }
            Optional<WorldState.ItemDrop> drop = world.getItemDrops().stream()
                    .filter(d -> d.itemId() == KpqConstants.ITEM_PASS).findFirst();
            if (drop.isPresent()) {
                pendingPickupOid = drop.get().objectId();
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
