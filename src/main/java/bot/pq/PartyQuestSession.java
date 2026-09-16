package bot.pq;

import bot.Planner;

/** Stateful execution of one definition for one participant in one event instance. */
public interface PartyQuestSession extends Planner {
    /** True while a move-then-act sequence must not be interrupted by healing or buffs. */
    default boolean midStep() {
        return false;
    }

    /**
     * True while this participant must stay on the spot it is standing on - KpqPlanner's puzzle
     * rectangles. Healing and buffs walk a companion back to its owner, which is exactly what must
     * not happen there, so the coordinator plans the session instead of interrupting it.
     */
    default boolean holdsPosition() {
        return false;
    }
}
