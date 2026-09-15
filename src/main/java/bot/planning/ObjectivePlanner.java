package bot.planning;

import bot.llm.Json;
import bot.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The thin objective planner shared by every kind of live bot. One call answers "what should this bot
 * be doing for the next few minutes" with a {@link Decision}; it never blocks a packet loop because
 * callers invoke it before a session starts or from their own planning thread, never per tick.
 *
 * <p>Decision path, cheapest first, logged for every decision:
 * <ol>
 *   <li><b>RULE</b> - {@link RulePlanner} is confident and the case isn't flagged for variety.</li>
 *   <li><b>CACHE</b> - an earlier LLM answer for the same {@link PlanCache#key key}, re-validated.</li>
 *   <li><b>LLM</b> - everything still wanting a model answer in one batch goes out as one request
 *       (up to {@link Settings#maxBatch} characters), with the stable prompt prefix and only a few
 *       retrieved knowledge excerpts; each character's answer is validated independently.</li>
 *   <li><b>FALLBACK</b> - the rule answer, when the gateway refused (no key, caps, no human online),
 *       the call failed, or that character's answer was missing or invalid.</li>
 * </ol>
 */
public final class ObjectivePlanner {
    private static final Logger log = LoggerFactory.getLogger(ObjectivePlanner.class);

    /**
     * @param llmForVariety  ask the model even when rules are confident, for cases flagged variety-eligible
     * @param maxBatch       characters per LLM request
     * @param baseMaxTokens  token allowance per request (reasoning + JSON)
     * @param perBotMaxTokens extra allowance per character in the request
     */
    public record Settings(boolean llmForVariety, int maxBatch, int baseMaxTokens, int perBotMaxTokens, int knowledgePages,
                           int knowledgeChars, double temperature) {}

    private final RulePlanner rules;
    private final PlanCache cache;
    private final LlmGateway llm;
    private final KnowledgeRetriever knowledge;
    private final Settings settings;

    private final AtomicLong ruleCount = new AtomicLong();
    private final AtomicLong cacheCount = new AtomicLong();
    private final AtomicLong llmCount = new AtomicLong();
    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong llmRequests = new AtomicLong();

    public ObjectivePlanner(RulePlanner rules, PlanCache cache, LlmGateway llm, KnowledgeRetriever knowledge, Settings settings) {
        this.rules = rules;
        this.cache = cache;
        this.llm = llm;
        this.knowledge = knowledge;
        this.settings = settings;
    }

    public Decision plan(PlanningContext ctx) {
        return planBatch(List.of(ctx)).get(0);
    }

    /** Decisions in the same order as {@code contexts}. */
    public List<Decision> planBatch(List<PlanningContext> contexts) {
        Decision[] out = new Decision[contexts.size()];
        RulePlanner.RuleDecision[] ruleDecisions = new RulePlanner.RuleDecision[contexts.size()];
        List<Integer> pending = new ArrayList<>();

        for (int i = 0; i < contexts.size(); i++) {
            PlanningContext ctx = contexts.get(i);
            RulePlanner.RuleDecision rd = rules.decide(ctx);
            ruleDecisions[i] = rd;
            boolean wantsModel = !rd.confident() || (rd.varietyEligible() && settings.llmForVariety());
            if (!wantsModel) {
                out[i] = new Decision(ctx.botName(), rd.objective(), Decision.Path.RULE, rd.reason());
                continue;
            }
            Optional<Objective> cached = cache.lookup(PlanCache.key(ctx), e -> ObjectiveCodec.adapt(e, ctx));
            if (cached.isPresent()) {
                out[i] = new Decision(ctx.botName(), cached.get(), Decision.Path.CACHE, "cached plan for " + PlanCache.key(ctx));
                continue;
            }
            pending.add(i);
        }

        for (int from = 0; from < pending.size(); from += Math.max(1, settings.maxBatch())) {
            List<Integer> chunk = pending.subList(from, Math.min(pending.size(), from + Math.max(1, settings.maxBatch())));
            askModel(contexts, ruleDecisions, chunk, out);
        }

        for (Decision d : out) {
            count(d.path());
            log.info("Planner: {} -> {} via {} ({}) [cache hit ratio {}]", d.botName(), Objective.summary(d.objective()),
                    d.path(), d.reason(), cacheHitRatio());
        }
        return List.of(out);
    }

    private void askModel(List<PlanningContext> contexts, RulePlanner.RuleDecision[] ruleDecisions, List<Integer> chunk,
                          Decision[] out) {
        List<PlanningContext> batch = new ArrayList<>();
        List<RulePlanner.RuleDecision> batchRules = new ArrayList<>();
        for (int i : chunk) {
            batch.add(contexts.get(i));
            batchRules.add(ruleDecisions[i]);
        }
        if (llm == null) {
            fallBack(contexts, ruleDecisions, chunk, out, "no LLM configured");
            return;
        }
        List<KnowledgeRetriever.Excerpt> excerpts = retrieve(batch);
        int maxTokens = PromptBuilder.maxTokens(settings.baseMaxTokens(), settings.perBotMaxTokens(), batch.size());
        LlmGateway.Outcome outcome = llm.call("plan", PromptBuilder.build(batch, batchRules, excerpts), maxTokens,
                settings.temperature(), -1);
        if (outcome.result() == null) {
            fallBack(contexts, ruleDecisions, chunk, out, "LLM " + outcome.refusal());
            return;
        }
        llmRequests.incrementAndGet();

        Map<String, Object> plans;
        try {
            plans = Json.asObject(Json.parseObjectLenient(outcome.result().content()).get("plans"), "plans");
        } catch (IllegalArgumentException e) {
            fallBack(contexts, ruleDecisions, chunk, out, "unparseable LLM reply: " + e.getMessage());
            return;
        }
        for (int i : chunk) {
            PlanningContext ctx = contexts.get(i);
            Object raw = findPlan(plans, ctx.botName());
            if (raw == null) {
                out[i] = new Decision(ctx.botName(), ruleDecisions[i].objective(), Decision.Path.FALLBACK,
                        "LLM reply had no plan for this character");
                continue;
            }
            try {
                Objective objective = ObjectiveCodec.decode(Json.asObject(raw, "plan"), ctx);
                cache.put(PlanCache.key(ctx), objective, ObjectiveCodec.priceFactors(objective, ctx));
                out[i] = new Decision(ctx.botName(), objective, Decision.Path.LLM,
                        "LLM plan (" + batch.size() + " character(s) in this request)");
            } catch (IllegalArgumentException e) {
                out[i] = new Decision(ctx.botName(), ruleDecisions[i].objective(), Decision.Path.FALLBACK,
                        "invalid LLM plan: " + e.getMessage());
            }
        }
    }

    private static Object findPlan(Map<String, Object> plans, String name) {
        for (Map.Entry<String, Object> e : plans.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** A few excerpts for the whole batch, de-duplicated; shopkeepers don't need training pages. */
    private List<KnowledgeRetriever.Excerpt> retrieve(List<PlanningContext> batch) {
        if (knowledge == null || settings.knowledgePages() <= 0) {
            return List.of();
        }
        Map<String, KnowledgeRetriever.Excerpt> merged = new LinkedHashMap<>();
        for (PlanningContext ctx : batch) {
            if (ctx.role() == PlanningContext.Role.RESIDENT) {
                continue;
            }
            for (KnowledgeRetriever.Excerpt e : knowledge.retrieve(KnowledgeRetriever.Query.of(ctx.jobLine(), ctx.level(), ctx.mapId()),
                    settings.knowledgePages(), settings.knowledgeChars())) {
                merged.putIfAbsent(e.path(), e);
            }
        }
        List<KnowledgeRetriever.Excerpt> list = new ArrayList<>(merged.values());
        int cap = settings.knowledgePages() * 2;
        return list.size() > cap ? list.subList(0, cap) : list;
    }

    private void fallBack(List<PlanningContext> contexts, RulePlanner.RuleDecision[] ruleDecisions, List<Integer> chunk,
                          Decision[] out, String reason) {
        for (int i : chunk) {
            out[i] = new Decision(contexts.get(i).botName(), ruleDecisions[i].objective(), Decision.Path.FALLBACK, reason);
        }
    }

    private void count(Decision.Path path) {
        switch (path) {
            case RULE -> ruleCount.incrementAndGet();
            case CACHE -> cacheCount.incrementAndGet();
            case LLM -> llmCount.incrementAndGet();
            case FALLBACK -> fallbackCount.incrementAndGet();
        }
    }

    /** Cache hits over every decision that wanted a model answer. */
    public String cacheHitRatio() {
        long hits = cacheCount.get();
        long wanted = hits + llmCount.get() + fallbackCount.get();
        return hits + "/" + wanted;
    }

    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("rule", ruleCount.get());
        m.put("cache", cacheCount.get());
        m.put("llm", llmCount.get());
        m.put("fallback", fallbackCount.get());
        m.put("llmRequests", llmRequests.get());
        return m;
    }
}
