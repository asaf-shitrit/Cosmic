package bot.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenRouter's OpenAI-compatible chat completions endpoint.
 *
 * <p><b>The key comes from the environment only</b> ({@code OPENROUTER_API_KEY}, loaded into the
 * container with {@code --env-file}/{@code env_file}); it is never logged, never put in an exception
 * message and never written anywhere. Error messages carry the HTTP status and a truncated body, which
 * OpenRouter does not echo the key into.
 *
 * <p><b>Reasoning model.</b> {@code deepseek/deepseek-v4-flash} spends part of {@code max_tokens} on
 * reasoning before any content; too small a budget comes back with empty content and
 * {@code finish_reason: stop}. Reasoning effort is asked to be low - these are short, constrained
 * choices - and the caller's budget covers both. Text is {@code message.content}.
 */
public final class OpenRouterClient implements LlmClient {
    public static final String ENV_KEY = "OPENROUTER_API_KEY";
    public static final String ENV_MODEL = "OPENROUTER_MODEL";
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4-flash";
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final HttpClient http;
    private final String apiKey;
    private final String model;

    private OpenRouterClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Empty when no key is configured - the caller then plans without an LLM. */
    public static Optional<LlmClient> fromEnvironment() {
        String key = System.getenv(ENV_KEY);
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String model = System.getenv(ENV_MODEL);
        return Optional.of(new OpenRouterClient(key.trim(), model == null || model.isBlank() ? DEFAULT_MODEL : model.trim()));
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Response complete(List<Message> messages, int maxTokens, double temperature) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Object> msgs = new ArrayList<>();
        for (Message m : messages) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.role());
            mm.put("content", m.content());
            msgs.add(mm);
        }
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("reasoning", Map.of("effort", "low"));
        body.put("usage", Map.of("include", true));

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-Title", "Cosmic v83 bots")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for OpenRouter");
        }
        long latency = System.currentTimeMillis() - started;
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OpenRouter HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return parse(response.body(), latency);
    }

    static Response parse(String responseBody, long latencyMs) throws IOException {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(responseBody), "response");
            if (root.containsKey("error")) {
                throw new IOException("OpenRouter error: " + truncate(Json.write(root.get("error"))));
            }
            List<Object> choices = Json.asList(root.get("choices"), "choices");
            if (choices.isEmpty()) {
                throw new IOException("OpenRouter returned no choices");
            }
            Map<String, Object> choice = Json.asObject(choices.get(0), "choice");
            Map<String, Object> message = Json.asObject(choice.get("message"), "message");
            Object content = message.get("content");
            String finish = choice.get("finish_reason") instanceof String s ? s : "";
            return new Response(content instanceof String s ? s : "", finish, usage(root.get("usage")), latencyMs);
        } catch (IllegalArgumentException e) {
            throw new IOException("unparseable OpenRouter response: " + e.getMessage());
        }
    }

    private static Usage usage(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            return Usage.NONE;
        }
        Map<String, Object> u = Json.asObject(raw, "usage");
        long prompt = number(u.get("prompt_tokens"));
        long completion = number(u.get("completion_tokens"));
        long cached = 0;
        if (u.get("prompt_tokens_details") instanceof Map<?, ?> d) {
            cached = number(d.get("cached_tokens"));
        }
        long reasoning = 0;
        if (u.get("completion_tokens_details") instanceof Map<?, ?> d) {
            reasoning = number(d.get("reasoning_tokens"));
        }
        double cost = u.get("cost") instanceof Number n ? n.doubleValue() : -1;
        return new Usage(prompt, cached, completion, reasoning, cost);
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
