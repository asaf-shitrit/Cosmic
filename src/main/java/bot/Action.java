package bot;

import java.awt.Point;
import java.util.List;

/**
 * One thing the bot can do in the game world. A {@link Planner} decides which action to take next;
 * {@link ActionExecutor} is the only class that knows how to turn one into the matching client-to-server
 * packet.
 *
 * <p>Keeping this as a closed set of plain data (rather than, say, letting a planner build packets
 * directly) is what lets a scripted {@link Planner} and a future LLM-backed one share the exact same
 * execution path - whoever picks the action, it gets carried out identically.
 */
public sealed interface Action {
    /**
     * Move to {@code target} via a single absolute-move fragment. MovePlayerHandler performs no
     * foothold or distance validation (see {@link ActionExecutor} for the packet-level proof), so
     * any in-bounds coordinate is accepted as-is - no pathfinding is required.
     */
    record MoveTo(Point target) implements Action {}

    /**
     * Opens dialogue with an NPC. {@code npcObjectId} is the map object id from {@link WorldState}
     * (i.e. what {@code SPAWN_NPC} called {@code objectId}), not the NPC template id - NPCTalkHandler
     * looks the target up by map object id.
     */
    record TalkToNpc(int npcObjectId) implements Action {}

    /** Sends one line of general chat. */
    record Say(String message) implements Action {}

    /**
     * Creates a brand-new party with this bot as leader. {@code PartyOperationHandler} operation 1;
     * no payload beyond the sub-opcode byte.
     */
    record CreateParty() implements Action {}

    /**
     * Invites {@code characterName} (must already be online, in this channel, unpartied) to this
     * bot's party. {@code PartyOperationHandler} operation 4.
     */
    record InviteToParty(String characterName) implements Action {}

    /**
     * Leaves the current party. {@code PartyOperationHandler} operation 2, no payload; if this bot is
     * the leader, the server disbands the party instead ({@code Party.leaveParty}).
     */
    record LeaveParty() implements Action {}

    /**
     * Accepts a pending party invite for {@code partyId}. {@code PartyOperationHandler} operation 3 -
     * the id must match one {@link WorldState#getPendingPartyInvite()} actually observed, since the
     * server resolves it via {@code InviteCoordinator} rather than trusting the id blindly.
     */
    record AcceptPartyInvite(int partyId) implements Action {}

    /**
     * Continues an NPC conversation already opened by a prior {@link TalkToNpc}. {@code lastMsgType}
     * must echo the type byte of the most recent {@code NPC_TALK} response (see
     * {@link WorldState#getLastNpcTalk()}) - {@code NPCMoreTalkHandler} uses it to decide how to
     * interpret {@code selection}. {@code proceed} is the "OK/Next/Yes" click; {@code selection} is
     * only meaningful for a "simple" (numbered-link, type 4) message and is ignored otherwise.
     */
    record RespondToNpc(int lastMsgType, boolean proceed, Integer selection) implements Action {}

    /**
     * Declares {@code damage} against a monster's single hit with a plain (skill 0) melee swing.
     * {@code AbstractDealDamageHandler} trusts this value outright when {@code skill == 0} - the
     * whole MP-cost/mob-count validation block is gated behind {@code attack.skill != 0} - and even
     * the autoban distance/damage sanity checks are no-ops with {@code USE_AUTOBAN: false} (see
     * {@code AbstractDealDamageHandler#parseDamage}/{@code #applyAttack}). So the damage declared is
     * the caller's choice; every caller rolls it like a client would ({@code bot.combat.DamageModel}),
     * 0 meaning a miss.
     */
    record AttackMonster(int monsterObjectId, int damage) implements Action {}

    /** A learned close-range skill attack. Damage is computed from the live character stats. */
    record SkillAttackMonster(int monsterObjectId, int skillId, int damage) implements Action {}

    /**
     * A learned attack spell ({@code MAGIC_ATTACK}) on one monster, one declared damage per line (Magic
     * Claw: two), 0 meaning that line missed. The server spends the skill's MP when it applies it.
     */
    record MagicAttackMonster(int monsterObjectId, int skillId, List<Integer> damageLines) implements Action {
        public MagicAttackMonster {
            damageLines = List.copyOf(damageLines);
        }
    }

    /**
     * A bow or crossbow attack ({@code RANGED_ATTACK}) on one monster; {@code skillId} 0 is a plain shot.
     * The server picks and consumes the arrows itself, so the character must hold some.
     */
    record RangedAttackMonster(int monsterObjectId, int skillId, List<Integer> damageLines) implements Action {
        public RangedAttackMonster {
            damageLines = List.copyOf(damageLines);
        }
    }

    /** Uses a learned self/party skill through the ordinary SPECIAL_MOVE packet. */
    record CastSkill(int skillId, int skillLevel) implements Action {}

    /** Consumes one finite USE inventory item at the observed slot. */
    record UseItem(int itemId, int slot) implements Action {}

    /**
     * Picks up the map-dropped item {@code objectId}. {@code ItemPickupHandler} itself only rejects a
     * pickup more than 800/600 px from the character's server-side position - always in range here
     * since {@link ActionExecutor} moves the bot to the drop's tracked position first - but
     * {@code Character#pickupItem} underneath it has two more gates worth knowing about: a drop must
     * be at least 400ms old, and {@code MapItem#canBePickedBy} requires either being the original
     * owner or (party drops, which is the case that matters here) a member of the owner's party.
     * Either rejection is silent from this bot's point of view - no error text, the item simply stays
     * put - which is exactly why a caller must never retry this in a tight loop with no cooldown; see
     * {@code KpqPlanner}'s {@code ACTION_RETRY_COOLDOWN_MS} javadoc for what happened the one time
     * that was tried.
     */
    record PickupItem(int objectId) implements Action {}

    /**
     * Drops {@code quantity} of {@code itemId} from the ETC inventory onto the ground.
     * {@code ItemMoveHandler} resolves the slot itself from {@code itemId} via
     * {@link WorldState#getEtcSlot(int)} - dropped items spawn FFA ({@code dropType == 2}, see
     * {@code InventoryManipulator#drop} -> {@code MapleMap#spawnItemDrop(..., ffaDrop=true, ...)}),
     * so any other party member can pick it straight back up with no ownership timer to race.
     */
    record DropItem(int itemId, int quantity) implements Action {}

    /**
     * Walks through a named portal on the current map. {@code ChangeMapHandler} resolves the target
     * purely from {@code portalName} looked up on the character's current map (not from a client-sent
     * target map id - that field is only honoured for GM warps) and then runs the portal's own script
     * ({@code enter(pi)}), which for KPQ's {@code next00} portals gates on the relevant
     * {@code NstageClear} eim property - safe to attempt speculatively before the stage is actually
     * clear, since a rejected attempt just leaves the character in place with a drop message, no
     * packet-level error.
     */
    record UsePortal(String portalName) implements Action {}

    /**
     * Asks to move to another channel ({@code channel} is 1-based, as players count them).
     * {@code ChangeChannelHandler} reads a zero-indexed byte and an unused int; asking for the current
     * channel is treated as a hack and disconnects. The server answers with a channel-change packet
     * pointing at the new channel's port - following it (reconnecting) is not implemented, so after
     * this the bot is effectively logged out.
     */
    record ChangeChannel(int channel) implements Action {}

    /** Nothing to do this tick. */
    record Idle() implements Action {}
}
