package bot.pq;

/** Static quest data plus the factory for one participant's stateful session. */
public interface PartyQuestDefinition {
    String id();

    String displayName();

    boolean ownsMap(int mapId);

    PartyQuestTeamPolicy teamPolicy();

    /** Completion/reward maps may continue an established session after the owner exits first. */
    default boolean requiresReadyTeam(int mapId) {
        return true;
    }

    PartyQuestSession createSession(PartyQuestContext context, PartyQuestCombat combat);
}
