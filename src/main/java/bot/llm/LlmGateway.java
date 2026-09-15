package bot.llm;

import bot.budget.UsageLedger;
import bot.budget.UsageLedger.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The only way bot code reaches an LLM. It decides whether a call may happen at all and records what
 * it cost; callers get an {@link Outcome} carrying a {@link Refusal} for every reason a call didn't
 * produce content, and must already have a deterministic answer to fall back on.
 *
 * <p>A call is refused, without touching the network, when: no client is configured (no key),
 * the feature is disabled, no human is online (when that gate is on), or the daily / hourly caps (and
 * the purpose's own hourly cap, e.g. chat) are spent. Caps are consumed before the request, so a
 * failing provider cannot be retried past them.
 *
 * <p>Every call that is made logs one INFO line with purpose, latency, token counts (prompt, of which
 * cached, completion, of which reasoning) and OpenRouter's reported cost, and adds those to daily
 * ledger counters so a day's spend can be read back from {@code bot_usage}. The LLM's text never goes
 * to the log - only its size - so a prompt-injected reply can't forge log lines.
 */
public final class LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    public static final String CALLS = "llm.calls";

    /**
     * @param dailyCallCap    all purposes together, per calendar day
     * @param hourlyCallCap   all purposes together, per clock hour
     * @param requireHuman    refuse calls while no human is online
     */
    public record Limits(boolean enabled, int dailyCallCap, int hourlyCallCap, boolean requireHuman) {}

    public enum Refusal { NO_CLIENT, DISABLED, NO_HUMAN, DAILY_CAP, HOURLY_CAP, PURPOSE_CAP, FAILED, EMPTY }

    public record Result(String content, LlmClient.Usage usage, long latencyMs) {}

    /** What a call attempt produced: a result, or the reason there is none. */
    public record Outcome(Result result, Refusal refusal) {
        public Optional<Result> content() {
            return Optional.ofNullable(result);
        }
    }

    private final LlmClient client;
    private final UsageLedger ledger;
    private final Limits limits;
    private final BooleanSupplier humanOnline;

    public LlmGateway(LlmClient client, UsageLedger ledger, Limits limits, BooleanSupplier humanOnline) {
        this.client = client;
        this.ledger = ledger;
        this.limits = limits;
        this.humanOnline = humanOnline;
    }

    /**
     * @param purpose      short tag for logs and the per-purpose counter, e.g. {@code plan} or {@code chat}
     * @param purposeHourlyCap extra per-hour cap for this purpose, or a negative number for none
     */
    public Outcome call(String purpose, List<LlmClient.Message> messages, int maxTokens, double temperature,
                        int purposeHourlyCap) {
        Refusal refusal = checkAndConsume(purpose, purposeHourlyCap);
        if (refusal != null) {
            log.info("LLM {} call skipped: {}", purpose, refusal);
            return new Outcome(null, refusal);
        }
        try {
            LlmClient.Response response = client.complete(messages, maxTokens, temperature);
            LlmClient.Usage u = response.usage();
            ledger.record("llm.prompt_tokens", Window.DAY, u.promptTokens());
            ledger.record("llm.cached_tokens", Window.DAY, u.cachedPromptTokens());
            ledger.record("llm.completion_tokens", Window.DAY, u.completionTokens());
            ledger.record("llm.reasoning_tokens", Window.DAY, u.reasoningTokens());
            if (u.costUsd() >= 0) {
                ledger.record("llm.cost_microusd", Window.DAY, Math.round(u.costUsd() * 1_000_000));
            }
            log.info("LLM {} call: model={} latency={}ms finish={} tokens prompt={} (cached {}) completion={} (reasoning {}) cost=${} [day: {} calls]",
                    purpose, client.model(), response.latencyMs(), response.finishReason(), u.promptTokens(),
                    u.cachedPromptTokens(), u.completionTokens(), u.reasoningTokens(),
                    u.costUsd() >= 0 ? String.format("%.6f", u.costUsd()) : "n/a", ledger.used(CALLS, Window.DAY));
            if (response.content() == null || response.content().isBlank()) {
                log.warn("LLM {} call returned no content (finish={}); the reasoning budget may be too small", purpose,
                        response.finishReason());
                return new Outcome(null, Refusal.EMPTY);
            }
            return new Outcome(new Result(response.content(), u, response.latencyMs()), null);
        } catch (IOException | RuntimeException e) {
            log.warn("LLM {} call failed: {}", purpose, e.getMessage());
            return new Outcome(null, Refusal.FAILED);
        }
    }

    private synchronized Refusal checkAndConsume(String purpose, int purposeHourlyCap) {
        if (client == null) {
            return Refusal.NO_CLIENT;
        }
        if (!limits.enabled()) {
            return Refusal.DISABLED;
        }
        if (limits.requireHuman() && !humanOnline.getAsBoolean()) {
            return Refusal.NO_HUMAN;
        }
        if (ledger.used(CALLS, Window.DAY) >= limits.dailyCallCap()) {
            return Refusal.DAILY_CAP;
        }
        if (ledger.used(CALLS, Window.HOUR) >= limits.hourlyCallCap()) {
            return Refusal.HOURLY_CAP;
        }
        String purposeCounter = "llm.calls." + purpose;
        if (purposeHourlyCap >= 0 && !ledger.tryConsume(purposeCounter, Window.HOUR, 1, purposeHourlyCap)) {
            return Refusal.PURPOSE_CAP;
        }
        // Both windows were checked above under this gateway's lock; count the call in each.
        ledger.record(CALLS, Window.DAY, 1);
        ledger.record(CALLS, Window.HOUR, 1);
        return null;
    }

    public boolean hasClient() {
        return client != null;
    }

    public long callsToday() {
        return ledger.used(CALLS, Window.DAY);
    }
}
