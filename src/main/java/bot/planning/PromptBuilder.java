package bot.planning;

import bot.llm.Json;
import bot.llm.LlmClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds planning prompts with a byte-identical prefix on every call - the system message holds the
 * role, the rules, the vocabulary and the reply schema, and nothing that varies - with all per-bot
 * content last. OpenRouter passes DeepSeek's automatic prefix caching through, so the stable part is
 * billed at the cached rate after the first call.
 */
public final class PromptBuilder {
    static final String SYSTEM = """
            You plan objectives for AI-controlled characters living in a private MapleStory v83 world \
            (pre-Big-Bang, early 2010). Scripted code carries out whatever you choose, so reply with data only.

            Rules:
            - Only v83 content exists. Never use or mention Evan, Mercedes, Cannoneer, Luminous, Resistance, \
            Star Force, Link Skills, Hyper Stats or Arcane River.
            - Use only ids that appear in the request (candidate items, map ids from the knowledge excerpts, \
            the rule suggestion). Never invent ids.
            - Shop prices are mesos per bundle and must stay between 0.5x and 2x of that item's rulePrice.
            - A RESIDENT character that has candidates must answer RunShop, listing at least half of maxListings \
            candidates (or all of them if there are fewer).
            - Shop titles are at most 24 plain ASCII characters, written in the character's own voice.
            - Prefer the rule suggestion unless the persona, recent sales or the knowledge give a reason to \
            vary it. Variety should look like a real player's habits, not randomness.
            - Reply with exactly one JSON object and no other text.

            Objective vocabulary (exact field names):
            {"objective":"TrainAt","mapId":0,"mobIds":[0]}
            {"objective":"Travel","mapId":0}
            {"objective":"RunShop","title":"","listings":[{"itemId":0,"bundles":0,"price":0}]}
            {"objective":"BuyFrom","seller":"","itemId":0,"quantity":0,"maxPrice":0}
            {"objective":"Restock","itemId":0,"quantity":0}
            {"objective":"SellTo","npcId":0}
            {"objective":"Idle","kind":"SIT|STAND|WANDER|CHAT","seconds":0}

            Reply format, one entry per character in the request:
            {"plans":{"<character name>":<objective>}}
            """;

    private PromptBuilder() {
    }

    public static List<LlmClient.Message> build(List<PlanningContext> contexts, List<RulePlanner.RuleDecision> ruleDecisions,
                                                List<KnowledgeRetriever.Excerpt> excerpts) {
        List<Object> bots = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            bots.add(describe(contexts.get(i), ruleDecisions.get(i)));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("characters", bots);
        if (!excerpts.isEmpty()) {
            List<Object> k = new ArrayList<>();
            for (KnowledgeRetriever.Excerpt e : excerpts) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("page", e.path());
                m.put("title", e.title());
                m.put("verified", e.verified());
                m.put("mapIds", e.mapIds());
                m.put("text", e.text());
                k.add(m);
            }
            request.put("knowledge", k);
        }
        return List.of(LlmClient.Message.system(SYSTEM), LlmClient.Message.user(Json.write(request)));
    }

    private static Map<String, Object> describe(PlanningContext ctx, RulePlanner.RuleDecision rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ctx.botName());
        m.put("role", ctx.role().name());
        if (ctx.persona() != null) {
            m.put("traits", ctx.persona().traits());
            m.put("focus", ctx.persona().focus());
            m.put("about", ctx.persona().blurb());
        }
        m.put("job", ctx.jobLine());
        m.put("level", ctx.level());
        m.put("mapId", ctx.mapId());
        m.put("hp", ctx.hp() + "/" + ctx.maxHp());
        m.put("mp", ctx.mp() + "/" + ctx.maxMp());
        m.put("mesos", ctx.mesos());
        if (!ctx.inventory().isEmpty()) {
            m.put("inventory", ctx.inventory());
        }
        if (!ctx.recentEpisodes().isEmpty()) {
            m.put("recent", ctx.recentEpisodes());
        }
        Map<String, Object> suggestion = ObjectiveCodec.encode(rule.objective());
        suggestion.put("why", rule.reason());
        m.put("ruleSuggestion", suggestion);
        if (ctx.shop() != null) {
            PlanningContext.ShopContext shop = ctx.shop();
            List<Object> candidates = new ArrayList<>();
            for (PlanningContext.Candidate c : shop.candidates()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("itemId", c.itemId());
                cm.put("name", c.name());
                cm.put("perBundle", c.perBundle());
                cm.put("maxBundles", c.maxBundles());
                cm.put("rulePrice", c.rulePricePerBundle());
                candidates.add(cm);
            }
            m.put("candidates", candidates);
            m.put("maxListings", shop.maxListings());
            m.put("sales", shop.salesSummary());
        }
        return m;
    }

    /** Reasoning plus a compact JSON answer: a fixed allowance and a per-character allowance. */
    public static int maxTokens(int baseTokens, int perCharacterTokens, int characters) {
        return baseTokens + perCharacterTokens * characters;
    }
}
