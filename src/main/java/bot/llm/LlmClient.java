package bot.llm;

import java.io.IOException;
import java.util.List;

/** One chat-completion round trip. {@link OpenRouterClient} is the real one; tests use a fake. */
public interface LlmClient {
    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }

    /**
     * Token counts and cost as the provider reported them. {@code costUsd} is OpenRouter's own
     * {@code usage.cost}, negative when the provider didn't send one.
     */
    record Usage(long promptTokens, long cachedPromptTokens, long completionTokens, long reasoningTokens, double costUsd) {
        public static final Usage NONE = new Usage(0, 0, 0, 0, -1);
    }

    record Response(String content, String finishReason, Usage usage, long latencyMs) {}

    Response complete(List<Message> messages, int maxTokens, double temperature) throws IOException;

    String model();
}
