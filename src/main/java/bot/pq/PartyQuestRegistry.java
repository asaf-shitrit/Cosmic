package bot.pq;

import java.util.List;
import java.util.Optional;

/** The one catalog consulted by companion code; adding a PQ does not change its caller. */
public final class PartyQuestRegistry {
    private static final List<PartyQuestDefinition> DEFINITIONS = List.of(
            new KerningPartyQuest(),
            new HenesysPartyQuest()
    );

    private PartyQuestRegistry() {}

    public static Optional<PartyQuestDefinition> forMap(int mapId) {
        return DEFINITIONS.stream().filter(definition -> definition.ownsMap(mapId)).findFirst();
    }
}
