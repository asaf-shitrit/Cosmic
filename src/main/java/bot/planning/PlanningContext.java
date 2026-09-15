package bot.planning;

import java.util.List;
import java.util.Map;

/**
 * Everything the planner may base a decision on, assembled by the caller from ground truth (the
 * database or the live {@code Character}) plus the bot's {@code bot-memory} persona and recent history.
 * Level, map, HP/MP, mesos and inventory are always read fresh, never remembered.
 *
 * @param jobLine   {@code warrior}, {@code magician}, {@code bowman}, {@code thief}, {@code pirate},
 *                  {@code cygnus}, {@code aran} or {@code beginner} - the vocabulary {@code bot-knowledge}
 *                  frontmatter uses in {@code jobs}
 * @param inventory item id to quantity, only what is relevant for planning (consumables, stock)
 * @param shop      present for bots that run a shop, otherwise null
 */
public record PlanningContext(
        Role role,
        String botName,
        Persona persona,
        int jobId,
        String jobLine,
        int level,
        int mapId,
        int hp,
        int maxHp,
        int mp,
        int maxMp,
        long mesos,
        Map<Integer, Integer> inventory,
        List<String> recentEpisodes,
        ShopContext shop) {

    public enum Role { RESIDENT, AMBIENT, COMPANION }

    /** Stable identity from {@code bot-memory}: personality traits, trade/play focus and a short blurb. */
    public record Persona(List<String> traits, String focus, String blurb) {}

    /**
     * The shop-running part of the context. The rule layer has already built {@code rulePlan} from the
     * resident's catalog and market state; {@code candidates} is the full set of items the model may
     * choose from, each with the price rules would ask.
     */
    public record ShopContext(int roomMapId, List<Candidate> candidates, List<Objective.Listing> rulePlan, String ruleTitle,
                              String salesSummary, int maxListings) {}

    public record Candidate(int itemId, String name, int perBundle, int maxBundles, int rulePricePerBundle) {}

    /** Level band used in plan-cache keys: 1-9, 10-19, ... */
    public int levelBand() {
        return Math.max(0, level / 10);
    }
}
