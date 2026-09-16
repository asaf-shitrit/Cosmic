package bot.pq;

/**
 * Result of resolving a definition's team policy against live server state.
 *
 * <p>{@code ordinal} is this companion's index within the resolved team, not its summon slot.
 * Sessions divide work by it - {@code HenesysPartyQuest} plants six seed colours round-robin over
 * team size, {@code KpqPlanner} picks a puzzle rectangle by absolute ordinal - so it has to be a
 * dense {@code 0..count-1}. Summon slots are only dense while every summon is alive: a companion
 * that drops mid-instance leaves a gap, and using the slot as an ordinal then misassigns work and
 * falls outside {@link PartyQuestContext}'s invariant.
 */
public record PartyQuestTeam(String problem, int managedCompanionCount, int ordinal) {
    public static PartyQuestTeam ready(int ordinal, int managedCompanionCount) {
        return new PartyQuestTeam(null, managedCompanionCount, ordinal);
    }

    public static PartyQuestTeam blocked(String problem) {
        return new PartyQuestTeam(problem, 0, 0);
    }

    public boolean ready() {
        return problem == null;
    }
}
