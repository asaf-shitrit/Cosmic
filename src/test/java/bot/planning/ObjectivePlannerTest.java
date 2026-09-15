package bot.planning;

import bot.MutableClock;
import bot.budget.UsageLedger;
import bot.llm.Json;
import bot.llm.LlmClient;
import bot.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectivePlannerTest {
    private static final ObjectivePlanner.Settings SETTINGS = new ObjectivePlanner.Settings(true, 4, 800, 300, 2, 400, 0.6);

    /** Answers with whatever the test's function builds from the prompt, and records every prompt. */
    static final class ScriptedLlm implements LlmClient {
        final List<List<Message>> prompts = new ArrayList<>();
        final Function<String, String> reply;

        ScriptedLlm(Function<String, String> reply) {
            this.reply = reply;
        }

        @Override
        public Response complete(List<Message> messages, int maxTokens, double temperature) throws IOException {
            prompts.add(messages);
            return new Response(reply.apply(messages.get(1).content()), "stop", Usage.NONE, 1);
        }

        @Override
        public String model() {
            return "scripted";
        }
    }

    static PlanningContext resident(String name, List<String> traits) {
        List<PlanningContext.Candidate> candidates = List.of(
                new PlanningContext.Candidate(2000002, "White Potion", 20, 10, 6_400),
                new PlanningContext.Candidate(2000003, "Blue Potion", 20, 10, 4_000),
                new PlanningContext.Candidate(2000006, "Mana Elixir", 10, 5, 6_200));
        List<Objective.Listing> rulePlan = List.of(new Objective.Listing(2000002, 8, 20, 6_400), new Objective.Listing(2000003, 6, 20, 4_000));
        PlanningContext.ShopContext shop = new PlanningContext.ShopContext(910000003, candidates, rulePlan, "Potions here", "sold 40 White Potion", 3);
        return new PlanningContext(PlanningContext.Role.RESIDENT, name, new PlanningContext.Persona(traits, "potions", "A cheerful potion seller."),
                211, "magician", 45, 910000003, 900, 900, 1200, 1200, 50_000, Map.of(), List.of(), shop);
    }

    static PlanningContext adventurer(String name, int level, int map, Map<Integer, Integer> inventory, int hp) {
        return new PlanningContext(PlanningContext.Role.AMBIENT, name, new PlanningContext.Persona(List.of("steady"), "training", ""),
                100, "warrior", level, map, hp, 1000, 100, 100, 10_000, inventory, List.of(), null);
    }

    private static LlmGateway gateway(LlmClient client, int dailyCap) {
        return new LlmGateway(client, new UsageLedger(new UsageLedger.InMemoryStore(), MutableClock.at("2026-09-15T12:00:00Z")),
                new LlmGateway.Limits(true, dailyCap, 100, false), () -> true);
    }

    private static String shopReply(String... names) {
        StringBuilder sb = new StringBuilder("{\"plans\":{");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(names[i]).append("\":{\"objective\":\"RunShop\",\"title\":\"Fresh Elixirs!\",\"listings\":[")
                    .append("{\"itemId\":2000006,\"bundles\":5,\"price\":7000},{\"itemId\":2000002,\"bundles\":10,\"price\":6000}]}");
        }
        return sb.append("}}").toString();
    }

    private static ObjectivePlanner planner(LlmGateway gw, MutableClock clock) {
        return new ObjectivePlanner(new RulePlanner(null, null), new PlanCache(clock, 6 * 3_600_000L, 100), gw, null, SETTINGS);
    }

    @Test
    void confidentRulesWithoutVarietyNeverCallTheModel() {
        ScriptedLlm llm = new ScriptedLlm(p -> "{}");
        ObjectivePlanner planner = planner(gateway(llm, 10), MutableClock.at("2026-09-15T12:00:00Z"));
        Decision d = planner.plan(adventurer("Walt", 25, 105050000, Map.of(2000001, 5), 1000));
        assertEquals(Decision.Path.RULE, d.path());
        assertEquals(new Objective.Restock(2000001, 100), d.objective());
        assertTrue(llm.prompts.isEmpty());
    }

    @Test
    void threeResidentsWakingTogetherShareOneRequestAndTheNextWakeHitsTheCache() {
        MutableClock clock = MutableClock.at("2026-09-15T12:00:00Z");
        ScriptedLlm llm = new ScriptedLlm(p -> shopReply("Mira", "Oskar", "Pim"));
        ObjectivePlanner planner = planner(gateway(llm, 10), clock);

        List<Decision> first = planner.planBatch(List.of(
                resident("Mira", List.of("cheerful")), resident("Oskar", List.of("grumpy")), resident("Pim", List.of("shy"))));
        assertEquals(1, llm.prompts.size(), "one request for the whole wake window");
        assertTrue(first.stream().allMatch(d -> d.path() == Decision.Path.LLM));
        Objective.RunShop shop = assertInstanceOf(Objective.RunShop.class, first.get(0).objective());
        assertEquals("Fresh Elixirs!", shop.title());
        assertEquals(new Objective.Listing(2000006, 5, 10, 7_000), shop.listings().get(0));
        String system = llm.prompts.get(0).get(0).content();
        assertEquals(PromptBuilder.SYSTEM, system, "the system prefix is byte-identical, so prompt caching can hit");

        clock.advanceMs(3 * 3_600_000L);
        Decision again = planner.plan(resident("Mira", List.of("cheerful")));
        assertEquals(Decision.Path.CACHE, again.path());
        assertEquals(1, llm.prompts.size());
        Decision sameKeyOtherBot = planner.plan(resident("Nell", List.of("cheerful")));
        assertEquals(Decision.Path.CACHE, sameKeyOtherBot.path(), "same persona traits and focus share a plan");
        assertEquals("2/5", planner.cacheHitRatio());
    }

    @Test
    void cachedShopPlansAreRepricedAgainstTodaysRulePrices() {
        MutableClock clock = MutableClock.at("2026-09-15T12:00:00Z");
        ScriptedLlm llm = new ScriptedLlm(p -> shopReply("Mira"));
        ObjectivePlanner planner = planner(gateway(llm, 10), clock);
        planner.plan(resident("Mira", List.of("cheerful")));

        PlanningContext base = resident("Mira", List.of("cheerful"));
        List<PlanningContext.Candidate> pricier = base.shop().candidates().stream()
                .map(c -> new PlanningContext.Candidate(c.itemId(), c.name(), c.perBundle(), c.maxBundles(), c.rulePricePerBundle() * 2)).toList();
        PlanningContext later = new PlanningContext(base.role(), base.botName(), base.persona(), base.jobId(), base.jobLine(), base.level(),
                base.mapId(), base.hp(), base.maxHp(), base.mp(), base.maxMp(), base.mesos(), base.inventory(), base.recentEpisodes(),
                new PlanningContext.ShopContext(base.shop().roomMapId(), pricier, base.shop().rulePlan(), base.shop().ruleTitle(), "", 3));
        Objective.RunShop shop = (Objective.RunShop) planner.plan(later).objective();
        assertEquals(14_000, shop.listings().get(0).pricePerBundle(), "7000/6200 of the old rule price, applied to 12400");
    }

    @Test
    void invalidOrRefusedModelAnswersFallBackToTheRulePlan() {
        MutableClock clock = MutableClock.at("2026-09-15T12:00:00Z");
        ScriptedLlm inventing = new ScriptedLlm(p -> "{\"plans\":{\"Mira\":{\"objective\":\"RunShop\",\"listings\":[{\"itemId\":1302000,\"bundles\":1,\"price\":1}]}}}");
        Decision invalid = planner(gateway(inventing, 10), clock).plan(resident("Mira", List.of("cheerful")));
        assertEquals(Decision.Path.FALLBACK, invalid.path());
        assertTrue(invalid.reason().contains("not a candidate"));
        assertEquals("Potions here", ((Objective.RunShop) invalid.objective()).title());

        ScriptedLlm unused = new ScriptedLlm(p -> shopReply("Mira"));
        Decision capped = planner(gateway(unused, 0), clock).plan(resident("Mira", List.of("cheerful")));
        assertEquals(Decision.Path.FALLBACK, capped.path());
        assertTrue(capped.reason().contains("DAILY_CAP"));
        assertTrue(unused.prompts.isEmpty());

        Decision noLlm = new ObjectivePlanner(new RulePlanner(null, null), new PlanCache(clock, 1000, 10), null, null, SETTINGS)
                .plan(resident("Mira", List.of("cheerful")));
        assertEquals(Decision.Path.FALLBACK, noLlm.path());
        assertInstanceOf(Objective.RunShop.class, noLlm.objective());

        ScriptedLlm prose = new ScriptedLlm(p -> "I think Mira should sell potions.");
        assertEquals(Decision.Path.FALLBACK, planner(gateway(prose, 10), clock).plan(resident("Mira", List.of("cheerful"))).path());
    }

    @Test
    void codecRejectsUnknownObjectivesAndOutOfRangePrices() {
        PlanningContext ctx = resident("Mira", List.of("cheerful"));
        assertThrows(IllegalArgumentException.class, () -> ObjectiveCodec.decode(Map.of("objective", "Teleport", "mapId", 1L), ctx));
        assertThrows(IllegalArgumentException.class, () -> ObjectiveCodec.decode(Json.parseObjectLenient(
                "{\"objective\":\"RunShop\",\"listings\":[{\"itemId\":2000002,\"bundles\":1,\"price\":100000}]}"), ctx));
        assertThrows(IllegalArgumentException.class, () -> ObjectiveCodec.decode(Json.parseObjectLenient(
                "{\"objective\":\"RunShop\",\"listings\":[{\"itemId\":2000002,\"bundles\":11,\"price\":6400}]}"), ctx));
        assertThrows(IllegalArgumentException.class, () -> ObjectiveCodec.decode(Map.of("objective", "Idle", "kind", "DANCE", "seconds", 10L), ctx));
        Objective idle = ObjectiveCodec.decode(Map.of("objective", "Idle", "kind", "sit", "seconds", 30L), ctx);
        assertEquals(new Objective.Idle(Objective.IdleKind.SIT, 30), idle);
        assertEquals("Cheap pots come get em!!", ObjectiveCodec.sanitizeTitle("  Cheap\tpots ✨ come get em!!!!!!!!"));
        for (Objective o : List.of(idle, new Objective.TrainAt(105050000, List.of(2230101)), new Objective.Travel(100000000),
                new Objective.BuyFrom("Hero", 2040002, 1, 9000), new Objective.Restock(2000000, 100), new Objective.SellTo(1011000))) {
            assertEquals(o, ObjectiveCodec.decode(Json.asObject(Json.parse(Json.write(ObjectiveCodec.encode(o))), "o"), ctx));
        }
    }

    @Test
    void rulesRestTrainAndWander() {
        RulePlanner.MapMobs mobs = new RulePlanner.MapMobs() {
            @Override
            public List<Integer> mobLevels(int mapId) {
                return mapId == 105050000 ? List.of(22, 24) : mapId == 101030400 ? List.of(4, 10) : List.of();
            }

            @Override
            public List<Integer> mobIds(int mapId) {
                return mapId == 105050000 ? List.of(2130100) : List.of();
            }
        };
        KnowledgeRetriever none = null;
        RulePlanner rules = new RulePlanner(none, mobs);
        assertEquals(Objective.IdleKind.SIT, ((Objective.Idle) rules.decide(adventurer("W", 25, 1, Map.of(2000001, 50), 200)).objective()).kind());
        RulePlanner.RuleDecision wander = rules.decide(adventurer("W", 25, 1, Map.of(2000001, 50), 1000));
        assertTrue(!wander.confident());
        assertEquals(RulePlanner.ORANGE_POTION, RulePlanner.potionFor(25));
        assertEquals(RulePlanner.RED_POTION, RulePlanner.potionFor(9));
    }
}
