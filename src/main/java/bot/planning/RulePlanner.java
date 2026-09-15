package bot.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The deterministic layer: answers the common cases with zero LLM calls, and always has <em>some</em>
 * answer so the planner can fall back to it. Rules, in order:
 *
 * <ol>
 *   <li><b>Shopkeepers</b> run their shop with the rule plan the resident code already priced -
 *       confident, but eligible for LLM variety (title, picks, pricing within bounds).</li>
 *   <li><b>Resting</b>: below {@link #REST_HP_RATIO} of max HP, sit.</li>
 *   <li><b>Potions</b>: from level {@link #POTION_RULE_MIN_LEVEL}, fewer than {@link #POTION_MIN} HP
 *       potions means restock the level-appropriate NPC potion.</li>
 *   <li><b>Training</b>: the {@code bot-knowledge} training pages for the bot's level name the maps.
 *       Already on one of them: train here. Otherwise the first listed map - confident, but when there
 *       are several good maps the LLM may pick for variety.</li>
 *   <li>Nothing matched: wander a little - not confident, so the LLM is asked when available.</li>
 * </ol>
 */
public final class RulePlanner {
    static final double REST_HP_RATIO = 0.3;
    static final int POTION_MIN = 20;
    static final int POTION_RULE_MIN_LEVEL = 10;
    static final int POTION_RESTOCK = 100;

    /** Red, Orange, White Potion: the NPC HP potions of the 1-15, 15-35 and 35+ bands (scripts' shops sell all three). */
    static final int RED_POTION = 2000000;
    static final int ORANGE_POTION = 2000001;
    static final int WHITE_POTION = 2000002;
    /** HP-restoring consumables per {@code String.wz/Consume.img}: the three potions, Elixir, Power Elixir, and food. */
    private static final Set<Integer> HP_POTIONS = Set.of(RED_POTION, ORANGE_POTION, WHITE_POTION, 2000004, 2000005,
            2001000, 2001001, 2001002, 2020000, 2020012, 2020013, 2020014);

    public record RuleDecision(Objective objective, boolean confident, boolean varietyEligible, String reason) {}

    /** Map spawn data, so a rule can check a knowledge page's maps against the bot's level. Optional. */
    public interface MapMobs {
        /** Levels of the monsters that spawn on {@code mapId}; empty if unknown. */
        List<Integer> mobLevels(int mapId);

        List<Integer> mobIds(int mapId);
    }

    private final KnowledgeRetriever knowledge;
    private final MapMobs mapMobs;

    public RulePlanner(KnowledgeRetriever knowledge, MapMobs mapMobs) {
        this.knowledge = knowledge;
        this.mapMobs = mapMobs;
    }

    public RuleDecision decide(PlanningContext ctx) {
        if (ctx.shop() != null && ctx.role() == PlanningContext.Role.RESIDENT) {
            PlanningContext.ShopContext shop = ctx.shop();
            if (shop.rulePlan().isEmpty()) {
                return new RuleDecision(new Objective.Idle(Objective.IdleKind.SIT, 300), true, false,
                        "resident has nothing to list");
            }
            return new RuleDecision(new Objective.RunShop(shop.roomMapId(), shop.ruleTitle(), shop.rulePlan()), true, true,
                    "resident shop rule plan");
        }
        if (ctx.maxHp() > 0 && ctx.hp() < ctx.maxHp() * REST_HP_RATIO) {
            return new RuleDecision(new Objective.Idle(Objective.IdleKind.SIT, 30), true, false, "HP below 30%");
        }
        if (ctx.level() >= POTION_RULE_MIN_LEVEL && hpPotions(ctx) < POTION_MIN) {
            return new RuleDecision(new Objective.Restock(potionFor(ctx.level()), POTION_RESTOCK), true, false,
                    "fewer than " + POTION_MIN + " HP potions");
        }
        List<Integer> maps = trainingMaps(ctx);
        if (maps.contains(ctx.mapId())) {
            return new RuleDecision(new Objective.TrainAt(ctx.mapId(), mobIds(ctx.mapId())), true, false,
                    "already on a training map for level " + ctx.level());
        }
        if (!maps.isEmpty()) {
            int map = maps.get(0);
            return new RuleDecision(new Objective.TrainAt(map, mobIds(map)), true, maps.size() > 1,
                    maps.size() + " training map(s) for level " + ctx.level());
        }
        return new RuleDecision(new Objective.Idle(Objective.IdleKind.WANDER, 120), false, false, "no rule matched");
    }

    static int potionFor(int level) {
        if (level < 15) {
            return RED_POTION;
        }
        return level < 35 ? ORANGE_POTION : WHITE_POTION;
    }

    private static int hpPotions(PlanningContext ctx) {
        return ctx.inventory().entrySet().stream().filter(e -> HP_POTIONS.contains(e.getKey()))
                .mapToInt(java.util.Map.Entry::getValue).sum();
    }

    /** Maps named by the level's training pages, keeping only those whose mobs suit the level when spawn data is known. */
    List<Integer> trainingMaps(PlanningContext ctx) {
        if (knowledge == null) {
            return List.of();
        }
        KnowledgeRetriever.Query q = new KnowledgeRetriever.Query(ctx.jobLine(), ctx.level(), ctx.mapId(), Set.of("training"));
        List<Integer> maps = new ArrayList<>();
        for (KnowledgeRetriever.Excerpt e : knowledge.retrieve(q, 3, 0)) {
            for (int map : e.mapIds()) {
                if (!maps.contains(map) && suits(map, ctx.level())) {
                    maps.add(map);
                }
            }
        }
        return maps;
    }

    private boolean suits(int mapId, int level) {
        if (mapMobs == null) {
            return true;
        }
        List<Integer> levels = mapMobs.mobLevels(mapId);
        if (levels.isEmpty()) {
            return true;       // unknown spawn data isn't evidence against the curated page
        }
        return levels.stream().anyMatch(l -> l >= level - 12 && l <= level + 5);
    }

    private List<Integer> mobIds(int mapId) {
        return mapMobs == null ? List.of() : mapMobs.mobIds(mapId);
    }
}
