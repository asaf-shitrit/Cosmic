package bot;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;

/**
 * Hardcoded stand-in for an LLM-backed {@link Planner}: walk to the nearest known NPC, talk to it,
 * say one line of chat, then go idle forever. Exists to prove the {@link WorldState}/
 * {@link ActionExecutor} wiring works end to end against the live server before a real
 * decision-maker drives it (see the seam described on {@link Planner}).
 *
 * <p>Not thread-safe and not reusable across objectives - one instance runs one fixed sequence once.
 */
public class ScriptedPlanner implements Planner {
    private enum Step { FIND_NPC, MOVE, TALK, SAY, DONE }

    private final String greeting;
    private Step step = Step.FIND_NPC;
    private WorldState.NpcSighting target;

    public ScriptedPlanner(String greeting) {
        this.greeting = greeting;
    }

    @Override
    public Action plan(WorldState world, Point selfPosition) {
        return switch (step) {
            case FIND_NPC -> {
                Optional<WorldState.NpcSighting> nearest = nearestNpc(world, selfPosition);
                if (nearest.isEmpty()) {
                    yield new Action.Idle();   // nothing spawned yet - the driver's floor will retry us
                }
                target = nearest.get();
                step = Step.MOVE;
                yield new Action.MoveTo(target.position());
            }
            case MOVE -> {
                // No move ack exists (see ActionExecutor#moveTo) - sending is the only completion
                // signal there is, so it's safe to advance as soon as the driver calls us again.
                step = Step.TALK;
                yield new Action.TalkToNpc(target.objectId());
            }
            case TALK -> {
                step = Step.SAY;
                yield new Action.Say(greeting);
            }
            case SAY -> {
                step = Step.DONE;
                yield new Action.Idle();
            }
            case DONE -> new Action.Idle();
        };
    }

    /** True once the scripted sequence has sent its last action. */
    public boolean isDone() {
        return step == Step.DONE;
    }

    private static Optional<WorldState.NpcSighting> nearestNpc(WorldState world, Point selfPosition) {
        // Without a real starting position (this bot never loads WZ portal data - see BotSession) we
        // can't measure distance on the very first move; fall back to a deterministic pick so the
        // sequence still makes progress. Once selfPosition is set - after our own first move - later
        // reruns would genuinely rank by distance.
        Comparator<WorldState.NpcSighting> byDistance = selfPosition == null
                ? Comparator.comparingInt(WorldState.NpcSighting::objectId)
                : Comparator.comparingDouble(n -> n.position().distanceSq(selfPosition));
        return world.getNpcs().stream().min(byDistance);
    }
}
