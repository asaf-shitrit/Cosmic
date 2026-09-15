package bot.planning;

import bot.llm.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a model's JSON into an {@link Objective} and checks it against the bot's context - the schema
 * validation step between "the LLM said something" and "scripted code does it". Every rule here is a
 * reason to fall back, not to fix the answer up silently:
 *
 * <ul>
 *   <li>{@code objective} must name a member of the closed vocabulary; unknown names are invalid, and a
 *       resident with a shop must answer {@code RunShop}</li>
 *   <li>ids must be positive integers; durations and quantities within sane bounds</li>
 *   <li>a shop plan may only list items the rules offered as candidates, at a price within
 *       {@link #MIN_PRICE_FACTOR}..{@link #MAX_PRICE_FACTOR} of the rule price, with the candidate's own
 *       bundle size - the model picks and prices, it cannot invent stock or mint value</li>
 *   <li>a shop title is plain printable ASCII, at most {@link #MAX_TITLE} characters</li>
 * </ul>
 */
public final class ObjectiveCodec {
    public static final double MIN_PRICE_FACTOR = 0.5;
    public static final double MAX_PRICE_FACTOR = 2.0;
    public static final int MAX_TITLE = 24;
    private static final int MAX_IDLE_SECONDS = 3600;

    private ObjectiveCodec() {
    }

    /** The objective names the model may use, for the prompt. */
    public static final List<String> VOCABULARY = List.of("TrainAt", "Travel", "RunShop", "BuyFrom", "Restock", "SellTo", "Idle");

    /**
     * @throws IllegalArgumentException with a short reason if the JSON isn't a valid objective for {@code ctx}
     */
    public static Objective decode(Map<String, Object> json, PlanningContext ctx) {
        String name = Json.asString(json.get("objective"), "objective");
        if (ctx.role() == PlanningContext.Role.RESIDENT && ctx.shop() != null && !"RunShop".equals(name)) {
            // Seen live: asked to plan an ores shop, the model answered Idle and the resident sat out a
            // whole wake with an empty stall. A shopkeeper's wake plan is the shop.
            throw new IllegalArgumentException("a resident with a shop must answer RunShop, not " + truncate(name));
        }
        return switch (name) {
            case "TrainAt" -> {
                int map = positiveInt(json.get("mapId"), "mapId");
                List<Integer> mobs = new ArrayList<>();
                if (json.containsKey("mobIds")) {
                    for (Object o : Json.asList(json.get("mobIds"), "mobIds")) {
                        mobs.add(positiveInt(o, "mobIds[]"));
                    }
                }
                yield new Objective.TrainAt(map, List.copyOf(mobs));
            }
            case "Travel" -> new Objective.Travel(positiveInt(json.get("mapId"), "mapId"));
            case "RunShop" -> decodeShop(json, ctx);
            case "BuyFrom" -> new Objective.BuyFrom(
                    nonBlank(json.get("seller"), "seller"),
                    positiveInt(json.get("itemId"), "itemId"),
                    boundedInt(json.get("quantity"), "quantity", 1, 100),
                    positiveInt(json.get("maxPrice"), "maxPrice"));
            case "Restock" -> new Objective.Restock(positiveInt(json.get("itemId"), "itemId"),
                    boundedInt(json.get("quantity"), "quantity", 1, 1000));
            case "SellTo" -> new Objective.SellTo(positiveInt(json.get("npcId"), "npcId"));
            case "Idle" -> {
                Objective.IdleKind kind;
                try {
                    kind = Objective.IdleKind.valueOf(Json.asString(json.get("kind"), "kind").toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unknown idle kind");
                }
                yield new Objective.Idle(kind, boundedInt(json.get("seconds"), "seconds", 1, MAX_IDLE_SECONDS));
            }
            default -> throw new IllegalArgumentException("unknown objective '" + truncate(name) + "'");
        };
    }

    private static Objective.RunShop decodeShop(Map<String, Object> json, PlanningContext ctx) {
        PlanningContext.ShopContext shop = ctx.shop();
        if (shop == null) {
            throw new IllegalArgumentException("RunShop for a bot without a shop");
        }
        Map<Integer, PlanningContext.Candidate> candidates = new HashMap<>();
        for (PlanningContext.Candidate c : shop.candidates()) {
            candidates.put(c.itemId(), c);
        }
        List<Objective.Listing> listings = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        for (Object o : Json.asList(json.get("listings"), "listings")) {
            Map<String, Object> l = Json.asObject(o, "listing");
            int itemId = positiveInt(l.get("itemId"), "itemId");
            PlanningContext.Candidate c = candidates.get(itemId);
            if (c == null) {
                throw new IllegalArgumentException("item " + itemId + " is not a candidate");
            }
            if (!seen.add(itemId)) {
                continue;
            }
            int bundles = boundedInt(l.get("bundles"), "bundles", 1, c.maxBundles());
            int price = positiveInt(l.get("price"), "price");
            double factor = (double) price / c.rulePricePerBundle();
            if (factor < MIN_PRICE_FACTOR || factor > MAX_PRICE_FACTOR) {
                throw new IllegalArgumentException("price " + price + " for item " + itemId + " is outside the allowed range");
            }
            listings.add(new Objective.Listing(itemId, bundles, c.perBundle(), price));
        }
        if (listings.isEmpty()) {
            throw new IllegalArgumentException("no listings");
        }
        if (listings.size() > shop.maxListings()) {
            listings = listings.subList(0, shop.maxListings());
        }
        String title = json.containsKey("title") ? sanitizeTitle(Json.asString(json.get("title"), "title")) : null;
        return new Objective.RunShop(shop.roomMapId(), title == null ? shop.ruleTitle() : title, List.copyOf(listings));
    }

    /** Printable ASCII only, collapsed whitespace, at most {@link #MAX_TITLE} characters; null if nothing usable is left. */
    public static String sanitizeTitle(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                sb.append(' ');
            } else if (ch > 0x20 && ch < 0x7F) {
                sb.append(ch);
            }
        }
        String t = sb.toString().replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_TITLE) {
            t = t.substring(0, MAX_TITLE).trim();
        }
        return t.isEmpty() ? null : t;
    }

    /** Each listing's price relative to the rule price in {@code ctx}, so a cached plan can be re-priced later. */
    public static Map<Integer, Double> priceFactors(Objective objective, PlanningContext ctx) {
        Map<Integer, Double> factors = new LinkedHashMap<>();
        if (objective instanceof Objective.RunShop shop && ctx.shop() != null) {
            for (Objective.Listing l : shop.listings()) {
                ctx.shop().candidates().stream().filter(c -> c.itemId() == l.itemId()).findFirst()
                        .ifPresent(c -> factors.put(l.itemId(), (double) l.pricePerBundle() / c.rulePricePerBundle()));
            }
        }
        return factors;
    }

    /**
     * Re-applies a cached objective to {@code target}. Shop plans keep their title, item picks and
     * relative prices but take today's rule prices and candidate limits; if fewer than half the cached
     * listings are still candidates the plan no longer fits and this returns empty. Other objectives
     * are reused as they are.
     */
    public static Optional<Objective> adapt(PlanCache.Entry entry, PlanningContext target) {
        if (!(entry.objective() instanceof Objective.RunShop cached)) {
            return Optional.of(entry.objective());
        }
        if (target.shop() == null) {
            return Optional.empty();
        }
        List<Objective.Listing> listings = new ArrayList<>();
        for (Objective.Listing l : cached.listings()) {
            PlanningContext.Candidate c = target.shop().candidates().stream().filter(x -> x.itemId() == l.itemId())
                    .findFirst().orElse(null);
            Double factor = entry.priceFactors().get(l.itemId());
            if (c == null || factor == null) {
                continue;
            }
            int price = (int) Math.max(1, Math.round(c.rulePricePerBundle() * factor));
            listings.add(new Objective.Listing(l.itemId(), Math.min(l.bundles(), c.maxBundles()), c.perBundle(), price));
        }
        if (listings.isEmpty() || listings.size() * 2 < cached.listings().size()) {
            return Optional.empty();
        }
        return Optional.of(new Objective.RunShop(target.shop().roomMapId(), cached.title(), List.copyOf(listings)));
    }

    /** JSON form of an objective, for the prompt's worked examples and for persisting a last plan. */
    public static Map<String, Object> encode(Objective o) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (o) {
            case Objective.TrainAt t -> {
                m.put("objective", "TrainAt");
                m.put("mapId", t.mapId());
                m.put("mobIds", t.mobIds());
            }
            case Objective.Travel t -> {
                m.put("objective", "Travel");
                m.put("mapId", t.mapId());
            }
            case Objective.RunShop r -> {
                m.put("objective", "RunShop");
                m.put("title", r.title());
                List<Object> ls = new ArrayList<>();
                for (Objective.Listing l : r.listings()) {
                    Map<String, Object> lm = new LinkedHashMap<>();
                    lm.put("itemId", l.itemId());
                    lm.put("bundles", l.bundles());
                    lm.put("price", l.pricePerBundle());
                    ls.add(lm);
                }
                m.put("listings", ls);
            }
            case Objective.BuyFrom b -> {
                m.put("objective", "BuyFrom");
                m.put("seller", b.sellerName());
                m.put("itemId", b.itemId());
                m.put("quantity", b.quantity());
                m.put("maxPrice", b.maxPricePerBundle());
            }
            case Objective.Restock r -> {
                m.put("objective", "Restock");
                m.put("itemId", r.itemId());
                m.put("quantity", r.quantity());
            }
            case Objective.SellTo s -> {
                m.put("objective", "SellTo");
                m.put("npcId", s.npcId());
            }
            case Objective.Idle i -> {
                m.put("objective", "Idle");
                m.put("kind", i.kind().name());
                m.put("seconds", i.seconds());
            }
        }
        return m;
    }

    private static int positiveInt(Object v, String field) {
        long n = Json.asLong(v, field);
        if (n <= 0 || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return (int) n;
    }

    private static int boundedInt(Object v, String field, int min, int max) {
        long n = Json.asLong(v, field);
        if (n < min || n > max) {
            throw new IllegalArgumentException(field + " must be in " + min + ".." + max);
        }
        return (int) n;
    }

    private static String nonBlank(Object v, String field) {
        String s = Json.asString(v, field).trim();
        if (s.isEmpty() || s.length() > 13) {
            throw new IllegalArgumentException(field + " must be a character name");
        }
        return s;
    }

    private static String truncate(String s) {
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
