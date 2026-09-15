package bot.planning;

/**
 * An objective plus how it was decided, so every decision can be logged with its path.
 *
 * <ul>
 *   <li>{@code RULE} - the deterministic layer was confident; no LLM involved</li>
 *   <li>{@code CACHE} - an earlier LLM plan for the same key, re-validated against this context</li>
 *   <li>{@code LLM} - a fresh, validated model answer</li>
 *   <li>{@code FALLBACK} - the model was wanted but refused, failed, or answered invalidly; this is the
 *       deterministic answer instead</li>
 * </ul>
 */
public record Decision(String botName, Objective objective, Path path, String reason) {
    public enum Path { RULE, CACHE, LLM, FALLBACK }
}
