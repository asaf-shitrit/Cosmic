package bot.planning;

import java.util.List;

/**
 * What a live bot should be doing next, at the level of "go train there" or "run your shop like this",
 * never packets. This is the closed vocabulary the planner may answer with - rules, the plan cache and
 * the LLM all produce one of these, and scripted code (a resident session, later an ambient or
 * companion executor) carries it out tick by tick. Anything the model returns outside this set is
 * rejected by {@link ObjectiveCodec} and replaced by the deterministic answer.
 *
 * <p>Distinct from {@code bot.Action}: an action is one packet-level step, an objective is minutes of
 * behaviour. A planner that returned actions would put an LLM round trip inside the movement loop.
 */
public sealed interface Objective {
    /** Grind {@code mobIds} on {@code mapId}. */
    record TrainAt(int mapId, List<Integer> mobIds) implements Objective {}

    /** Get to {@code mapId}. */
    record Travel(int mapId) implements Objective {}

    /**
     * Open or maintain a Hired Merchant in {@code roomMapId} with this title and these listings.
     * {@code pricePerBundle} is what {@code PlayerShopItem} stores: a buyer pays it once per bundle.
     */
    record RunShop(int roomMapId, String title, List<Listing> listings) implements Objective {}

    record Listing(int itemId, int bundles, int perBundle, int pricePerBundle) {}

    /** Buy up to {@code quantity} bundles of {@code itemId} from {@code sellerName}'s shop, at most {@code maxPricePerBundle} each. */
    record BuyFrom(String sellerName, int itemId, int quantity, int maxPricePerBundle) implements Objective {}

    /** Buy {@code quantity} of a consumable ({@code itemId}) from an NPC shop. */
    record Restock(int itemId, int quantity) implements Objective {}

    /** Sell loot to NPC {@code npcId}. */
    record SellTo(int npcId) implements Objective {}

    enum IdleKind { SIT, STAND, WANDER, CHAT }

    /** Do nothing in particular for a while, in a natural-looking way. */
    record Idle(IdleKind kind, int seconds) implements Objective {}

    /** One-line summary for decision logs. Never includes free text from the model except a short title. */
    static String summary(Objective o) {
        return switch (o) {
            case TrainAt t -> "TrainAt(map " + t.mapId() + ", mobs " + t.mobIds() + ")";
            case Travel t -> "Travel(map " + t.mapId() + ")";
            case RunShop r -> "RunShop(room " + r.roomMapId() + ", \"" + r.title() + "\", " + r.listings().size() + " listings)";
            case BuyFrom b -> "BuyFrom(" + b.sellerName() + ", item " + b.itemId() + " x" + b.quantity() + " <= " + b.maxPricePerBundle() + ")";
            case Restock r -> "Restock(item " + r.itemId() + " x" + r.quantity() + ")";
            case SellTo s -> "SellTo(npc " + s.npcId() + ")";
            case Idle i -> "Idle(" + i.kind() + ", " + i.seconds() + "s)";
        };
    }
}
