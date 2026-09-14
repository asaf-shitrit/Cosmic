package bot;

import java.awt.Point;

/**
 * Decides the next {@link Action} given the current {@link WorldState}. A planner is deliberately
 * unopinionated about *when* it gets called - a driver loop (see {@code BotSession}'s game loop)
 * owns cadence: it replans on notable {@link WorldState} changes (a spawn/despawn, or the planner's
 * own previous action completing) with a periodic floor as a fallback for an otherwise-idle bot.
 * Cadence is a parameter of that loop, not something baked into this interface, so a slow
 * network-bound implementation (e.g. one that calls out to an LLM) and a scripted instant one can
 * share the exact same driver.
 *
 * <p>{@link ScriptedPlanner} is the only implementation today. The intended next implementation is
 * an OpenRouter-backed one (model selection, prompting, JSON tool-call parsing) that turns a
 * {@link WorldState} snapshot into an {@link Action} the same way - that implementation is not part
 * of this change; this interface is the seam it plugs into.
 */
public interface Planner {
    /**
     * @param world the current map snapshot
     * @param selfPosition this bot's last known position, or {@code null} if it hasn't moved yet
     *                      this session (see {@link WorldState#getSelfPosition})
     * @return the next action to take; {@link Action.Idle} if there's nothing to do right now
     */
    Action plan(WorldState world, Point selfPosition);
}
