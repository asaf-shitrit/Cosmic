package bot.pq;

/** Stable facts about one managed participant for the lifetime of a PQ instance. */
public record PartyQuestContext(int ordinal, int ownerId, String ownerName,
                                int managedCompanionCount) {
    public PartyQuestContext {
        if (ordinal < 0 || ordinal >= managedCompanionCount) {
            throw new IllegalArgumentException("ordinal must identify a managed companion");
        }
    }
}
