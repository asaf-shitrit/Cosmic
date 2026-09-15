package bot.planning;

import bot.budget.BotBudget;
import bot.budget.DbCounterStore;
import bot.budget.HumanPresence;
import bot.budget.UsageLedger;
import bot.llm.LlmGateway;
import bot.llm.OpenRouterClient;
import config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;

/**
 * The one set of shared live-bot services in the server process: the connected-bot budget, the usage
 * ledger, the LLM gateway and the objective planner (with its cache and knowledge retrieval). Residents
 * use it today; ambient locals and summoned companions take the same instance, so caps, the plan cache
 * and the bot budget are genuinely shared rather than per feature.
 *
 * <p>Built lazily from {@code config.yaml} on first use.
 */
public record PlanningServices(BotBudget budget, UsageLedger ledger, LlmGateway gateway, ObjectivePlanner planner,
                               KnowledgeRetriever knowledge) {
    private static final Logger log = LoggerFactory.getLogger(PlanningServices.class);
    private static volatile PlanningServices shared;

    public static PlanningServices shared(ServerConfig s) {
        PlanningServices p = shared;
        if (p == null) {
            synchronized (PlanningServices.class) {
                if (shared == null) {
                    shared = build(s);
                }
                p = shared;
            }
        }
        return p;
    }

    private static PlanningServices build(ServerConfig s) {
        BotBudget budget = BotBudget.shared(s.BOT_BUDGET_MAX_CONNECTED, s.BOT_BUDGET_COMPANION_RESERVE);
        // Pre-merge bridge: summoned companions don't lease slots yet, so count the ones online.
        budget.setExternalUsage(HumanPresence::summonedCompanionsOnline);
        UsageLedger ledger = new UsageLedger(new DbCounterStore(), Clock.systemDefaultZone());
        LlmGateway gateway = new LlmGateway(OpenRouterClient.fromEnvironment().orElse(null), ledger,
                new LlmGateway.Limits(s.LLM_ENABLED, s.LLM_DAILY_CALL_CAP, s.LLM_HOURLY_CALL_CAP, s.LLM_REQUIRE_HUMAN_ONLINE),
                () -> HumanPresence.humansOnline() > 0);
        KnowledgeRetriever knowledge = new KnowledgeRetriever(Path.of(s.BOT_KNOWLEDGE_DIR));
        ObjectivePlanner planner = new ObjectivePlanner(
                new RulePlanner(knowledge, null),
                new PlanCache(Clock.systemUTC(), s.LLM_PLAN_CACHE_TTL_MINUTES * 60_000L, 256),
                gateway,
                knowledge,
                new ObjectivePlanner.Settings(s.LLM_PLAN_FOR_VARIETY, Math.max(1, s.LLM_PLAN_MAX_BATCH), s.LLM_PLAN_BASE_MAX_TOKENS,
                        s.LLM_PLAN_PER_BOT_MAX_TOKENS, 3, 700, 0.7));
        log.info("Live bot planning: budget {} connected bots ({} reserved for companions), LLM client {}, daily/hourly call caps {}/{}, "
                        + "knowledge bundle {}", s.BOT_BUDGET_MAX_CONNECTED, s.BOT_BUDGET_COMPANION_RESERVE,
                gateway.hasClient() ? "configured" : "absent (rules only)", s.LLM_DAILY_CALL_CAP, s.LLM_HOURLY_CALL_CAP,
                knowledge.available() ? "found" : "not found (no excerpts)");
        return new PlanningServices(budget, ledger, gateway, planner, knowledge);
    }
}
