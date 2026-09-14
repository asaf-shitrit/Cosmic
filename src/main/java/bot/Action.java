package bot;

import java.awt.Point;

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

    /** Nothing to do this tick. */
    record Idle() implements Action {}
}
