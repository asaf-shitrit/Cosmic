package bot.llm;

import bot.MutableClock;
import bot.budget.UsageLedger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmGatewayTest {
    private static final List<LlmClient.Message> PROMPT = List.of(LlmClient.Message.user("hi"));

    static final class FakeClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile String content = "{\"ok\":true}";
        volatile boolean fail;

        @Override
        public Response complete(List<Message> messages, int maxTokens, double temperature) throws IOException {
            calls.incrementAndGet();
            if (fail) {
                throw new IOException("boom");
            }
            return new Response(content, "stop", new Usage(1000, 800, 200, 150, 0.00012), 5);
        }

        @Override
        public String model() {
            return "fake";
        }
    }

    private static UsageLedger ledger(MutableClock clock) {
        return new UsageLedger(new UsageLedger.InMemoryStore(), clock);
    }

    @Test
    void noClientNeverCalls() {
        LlmGateway gw = new LlmGateway(null, ledger(MutableClock.at("2026-09-15T10:00:00Z")),
                new LlmGateway.Limits(true, 10, 10, false), () -> true);
        assertEquals(LlmGateway.Refusal.NO_CLIENT, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
    }

    @Test
    void noHumanOnlineRefusesWithoutTouchingTheNetwork() {
        FakeClient client = new FakeClient();
        AtomicBoolean human = new AtomicBoolean(false);
        LlmGateway gw = new LlmGateway(client, ledger(MutableClock.at("2026-09-15T10:00:00Z")),
                new LlmGateway.Limits(true, 10, 10, true), human::get);
        assertEquals(LlmGateway.Refusal.NO_HUMAN, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(0, client.calls.get());
        human.set(true);
        assertNotNull(gw.call("plan", PROMPT, 100, 0.5, -1).result());
        assertEquals(1, client.calls.get());
    }

    @Test
    void dailyAndHourlyCapsCountFailedCallsToo() {
        MutableClock clock = MutableClock.at("2026-09-15T10:00:00Z");
        FakeClient client = new FakeClient();
        client.fail = true;
        LlmGateway gw = new LlmGateway(client, ledger(clock), new LlmGateway.Limits(true, 3, 2, false), () -> true);

        assertEquals(LlmGateway.Refusal.FAILED, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(LlmGateway.Refusal.FAILED, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(LlmGateway.Refusal.HOURLY_CAP, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        clock.advanceMs(3_600_000);
        client.fail = false;
        assertNull(gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(LlmGateway.Refusal.DAILY_CAP, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(3, client.calls.get());
        assertEquals(3, gw.callsToday());
    }

    @Test
    void purposeCapIsSeparateFromTheGlobalCap() {
        FakeClient client = new FakeClient();
        LlmGateway gw = new LlmGateway(client, ledger(MutableClock.at("2026-09-15T10:00:00Z")),
                new LlmGateway.Limits(true, 100, 100, false), () -> true);
        assertNull(gw.call("chat", PROMPT, 100, 0.5, 2).refusal());
        assertNull(gw.call("chat", PROMPT, 100, 0.5, 2).refusal());
        assertEquals(LlmGateway.Refusal.PURPOSE_CAP, gw.call("chat", PROMPT, 100, 0.5, 2).refusal());
        assertNull(gw.call("plan", PROMPT, 100, 0.5, -1).refusal(), "planning isn't limited by the chat cap");
    }

    @Test
    void emptyContentIsNotASuccessAndDisabledRefuses() {
        FakeClient client = new FakeClient();
        client.content = "";
        UsageLedger ledger = ledger(MutableClock.at("2026-09-15T10:00:00Z"));
        LlmGateway gw = new LlmGateway(client, ledger, new LlmGateway.Limits(true, 10, 10, false), () -> true);
        assertEquals(LlmGateway.Refusal.EMPTY, gw.call("plan", PROMPT, 100, 0.5, -1).refusal());
        assertEquals(120, ledger.used("llm.cost_microusd", UsageLedger.Window.DAY));
        assertEquals(800, ledger.used("llm.cached_tokens", UsageLedger.Window.DAY));

        LlmGateway off = new LlmGateway(client, ledger, new LlmGateway.Limits(false, 10, 10, false), () -> true);
        assertEquals(LlmGateway.Refusal.DISABLED, off.call("plan", PROMPT, 100, 0.5, -1).refusal());
    }

    @Test
    void openRouterResponseParsingReadsContentAndUsage() throws IOException {
        String body = "{\"id\":\"x\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{\\\"plans\\\":{}}\",\"reasoning\":\"thinking...\"}}],\"usage\":{\"prompt_tokens\":1200,"
                + "\"completion_tokens\":300,\"prompt_tokens_details\":{\"cached_tokens\":1024},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":220},\"cost\":0.000215}}";
        LlmClient.Response r = OpenRouterClient.parse(body, 42);
        assertEquals("{\"plans\":{}}", r.content());
        assertEquals(1200, r.usage().promptTokens());
        assertEquals(1024, r.usage().cachedPromptTokens());
        assertEquals(220, r.usage().reasoningTokens());
        assertEquals(0.000215, r.usage().costUsd(), 1e-9);
    }
}
