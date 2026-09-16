package bot.pq;

/** Declarative party shape required before managed companions may act in a party quest. */
public record PartyQuestTeamPolicy(int minPartySize, int maxPartySize,
                                   int minManagedCompanions, int maxManagedCompanions) {
    public PartyQuestTeamPolicy {
        if (minPartySize < 1 || minPartySize > maxPartySize
                || minManagedCompanions < 1 || minManagedCompanions > maxManagedCompanions) {
            throw new IllegalArgumentException("invalid party quest team policy");
        }
    }
}
