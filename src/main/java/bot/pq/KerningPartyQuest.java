package bot.pq;

import bot.kpq.KpqPlanner;

final class KerningPartyQuest implements PartyQuestDefinition {
    private static final PartyQuestTeamPolicy TEAM = new PartyQuestTeamPolicy(4, 4, 3, 3);

    @Override
    public String id() {
        return "kerning";
    }

    @Override
    public String displayName() {
        return "Kerning Party Quest";
    }

    @Override
    public boolean ownsMap(int mapId) {
        return KpqPlanner.isStageMap(mapId);
    }

    @Override
    public PartyQuestTeamPolicy teamPolicy() {
        return TEAM;
    }

    @Override
    public PartyQuestSession createSession(PartyQuestContext context, PartyQuestCombat combat) {
        return KpqPlanner.summonedMember(context.ordinal(), context.ownerId(), context.ownerName(), combat::attack);
    }
}
