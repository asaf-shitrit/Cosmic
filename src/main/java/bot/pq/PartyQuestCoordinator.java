package bot.pq;

import bot.Action;
import bot.WorldState;
import bot.combat.CombatController;
import bot.combat.CombatScope;
import client.Character;
import scripting.event.EventInstanceManager;

import java.awt.Point;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Deep module used by a companion: recognizes a PQ, validates its team, owns its session and
 * arbitrates recovery/support without exposing quest-specific state to the caller.
 */
public final class PartyQuestCoordinator {
    @FunctionalInterface
    public interface TeamResolver {
        PartyQuestTeam resolve(PartyQuestTeamPolicy policy, Character self, Character owner);
    }

    private final int ownerId;
    private final String ownerName;
    private final Supplier<Character> selfSupplier;
    private final Supplier<Character> ownerSupplier;
    private final CombatController combat;
    private final TeamResolver teams;

    private PartyQuestDefinition definition;
    private PartyQuestSession session;
    private EventInstanceManager instance;
    private int managedCompanionCount;
    private int managedOrdinal;
    private String activity;

    public PartyQuestCoordinator(int ownerId, String ownerName,
                                 Supplier<Character> selfSupplier, Supplier<Character> ownerSupplier,
                                 CombatController combat, TeamResolver teams) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.selfSupplier = selfSupplier;
        this.ownerSupplier = ownerSupplier;
        this.combat = combat;
        this.teams = teams;
    }

    /** Empty outside registered PQ maps; present Idle still means an active PQ owns the tick. */
    public Optional<Action> planIfActive(WorldState world, Point position) {
        PartyQuestDefinition found = PartyQuestRegistry.forMap(world.getSelfMapId()).orElse(null);
        if (found == null) {
            reset();
            return Optional.empty();
        }
        Character self = selfSupplier.get();
        Character owner = ownerSupplier.get();
        if (self == null || owner == null) {
            activity = "waiting in " + found.displayName();
            return Optional.of(new Action.Idle());
        }
        EventInstanceManager currentInstance = self.getEventInstance();
        boolean continuingCompletion = !found.requiresReadyTeam(world.getSelfMapId())
                && session != null && definition == found && instance == currentInstance;
        PartyQuestTeam team = continuingCompletion
                ? PartyQuestTeam.ready(managedOrdinal, managedCompanionCount)
                : teams.resolve(found.teamPolicy(), self, owner);
        if (!team.ready()) {
            activity = "waiting for " + found.displayName() + ": " + team.problem();
            return Optional.of(new Action.Idle());
        }
        if (session == null || definition != found || instance != currentInstance
                || managedCompanionCount != team.managedCompanionCount()
                || managedOrdinal != team.ordinal()) {
            definition = found;
            instance = currentInstance;
            managedCompanionCount = team.managedCompanionCount();
            managedOrdinal = team.ordinal();
            PartyQuestContext context = new PartyQuestContext(
                    team.ordinal(), ownerId, ownerName, team.managedCompanionCount());
            session = definition.createSession(context, new PartyQuestCombat() {
                @Override
                public Action attack(WorldState world, Point position, int monsterObjectId) {
                    return combat.attackTarget(world, position, monsterObjectId, CombatScope.FULL_MAP);
                }

                @Override
                public int attackReach() {
                    return combat.attackReach();
                }
            });
        }
        activity = "helping with " + definition.displayName();
        if (session.midStep()) {
            return Optional.of(session.plan(world, position));
        }
        Action recover = combat.recover();
        if (!(recover instanceof Action.Idle)) {
            return Optional.of(recover);
        }
        // Support walks the companion back to its owner before it casts, so a heal or buff would
        // pull a puzzle participant off its rectangle; the session plans the tick instead.
        if (session.holdsPosition()) {
            return Optional.of(session.plan(world, position));
        }
        Action support = combat.support(world, position, false);
        return Optional.of(support instanceof Action.Idle ? session.plan(world, position) : support);
    }

    public String activity() {
        return activity;
    }

    private void reset() {
        definition = null;
        session = null;
        instance = null;
        managedCompanionCount = 0;
        managedOrdinal = 0;
        activity = null;
    }
}
