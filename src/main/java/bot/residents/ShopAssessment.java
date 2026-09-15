package bot.residents;

import bot.planning.Objective;
import bot.planning.PlanningContext;
import bot.residents.market.CatalogItem;
import bot.residents.market.ItemCatalog;
import bot.residents.market.MarketPricing;
import bot.residents.market.StockSelector;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Everything decided about a resident's shop before it logs in, from ground truth only:
 *
 * <ol>
 *   <li>What sold since last wake: units it listed minus units still in its open merchant or held by
 *       Fredrick (a merchant that closed for a restart or its 24 hours hands unsold stock to Fredrick).</li>
 *   <li>Supply on the market: the cheapest competing listing of each item on the channel.</li>
 *   <li>{@link MarketPricing#observe} with both, persisted.</li>
 *   <li>The rule plan ({@link StockSelector}) and the wider candidate list the LLM may choose from,
 *       wrapped in a {@link PlanningContext} for the shared planner.</li>
 * </ol>
 *
 * Doing this before login keeps the LLM round trip out of the session entirely: the session only
 * executes the objective it was handed.
 */
final class ShopAssessment {
    private static final Logger log = LoggerFactory.getLogger(ShopAssessment.class);

    final ResidentProfile profile;
    final int charId;
    final MarketPricing.State market;
    final List<CatalogItem> catalog;
    /** Units the resident will have to list from: inventory, plus what it will take back from its merchant or Fredrick. */
    final Map<Integer, Integer> stockUnits;
    final Map<Integer, Integer> soldUnits;
    final PlanningContext context;
    final long assessedAt;

    private ShopAssessment(ResidentProfile profile, int charId, MarketPricing.State market, List<CatalogItem> catalog,
                           Map<Integer, Integer> stockUnits, Map<Integer, Integer> soldUnits, PlanningContext context, long assessedAt) {
        this.profile = profile;
        this.charId = charId;
        this.market = market;
        this.catalog = catalog;
        this.stockUnits = stockUnits;
        this.soldUnits = soldUnits;
        this.context = context;
        this.assessedAt = assessedAt;
    }

    static ShopAssessment assess(ResidentProfile profile, ResidentConfig config, ResidentMemory memory, RandomGenerator rng, long now) {
        int charId = client.Character.getIdByName(profile.name());
        MarketPricing.State market = ResidentStore.loadMarket(profile.name());
        List<CatalogItem> catalog = ItemCatalog.get().forProfile(profile);

        Map<Integer, Integer> inventory = new HashMap<>();
        Map<Integer, Integer> stillListed = new HashMap<>();
        int[] stats = {profile.level(), profile.jobId(), FreeMarket.ENTRANCE, 0, 0, 0, 0, 0};
        if (charId > 0) {
            addItems(inventory, loadItems(ItemFactory.INVENTORY, charId));
            HiredMerchant hm = FreeMarket.ownMerchant(0, charId);
            if (hm != null) {
                for (PlayerShopItem it : new ArrayList<>(hm.getItems())) {
                    if (it.isExist() && it.getBundles() > 0) {
                        stillListed.merge(it.getItem().getItemId(), it.getBundles() * (int) it.getItem().getQuantity(), Integer::sum);
                    }
                }
            } else {
                // An open merchant mirrors its items into these MERCHANT rows on every change, so they are
                // only a separate stock when no merchant is open: what Fredrick holds after a close.
                addItems(stillListed, loadItems(ItemFactory.MERCHANT, charId));
            }
            readCharacter(charId, stats);
        }

        Map<Integer, Integer> cheapest = new HashMap<>();
        for (FreeMarket.Listing l : FreeMarket.listings(0, config.channel(), charId)) {
            cheapest.merge(l.itemId(), l.unitPrice(), Math::min);
        }

        Map<Integer, Integer> sold = new LinkedHashMap<>();
        List<String> salesParts = new ArrayList<>();
        for (CatalogItem item : catalog) {
            int listed = market.listedUnits(item.itemId());
            int remaining = stillListed.getOrDefault(item.itemId(), 0);
            int soldNow = Math.max(0, listed - remaining);
            if (listed > 0 || cheapest.containsKey(item.itemId())) {
                double before = market.multiplier(item.itemId());
                double after = MarketPricing.observe(market, item, listed, soldNow, cheapest.getOrDefault(item.itemId(), 0));
                if (listed > 0) {
                    sold.put(item.itemId(), soldNow);
                    salesParts.add(item.name() + " " + soldNow + "/" + listed + " sold, price x" + fmt(before) + "->x" + fmt(after));
                }
            }
            market.setListedUnits(item.itemId(), remaining);
        }
        ResidentStore.saveMarket(profile.name(), market);

        Map<Integer, Integer> stock = new HashMap<>(inventory);
        stillListed.forEach((id, units) -> stock.merge(id, units, Integer::sum));
        stock.keySet().removeIf(id -> catalog.stream().noneMatch(c -> c.itemId() == id));

        List<StockSelector.Pick> picks = StockSelector.select(catalog, market, config.maxListings(), stock, rng);
        List<CatalogItem> alternatives = StockSelector.alternatives(catalog, picks, config.maxListings(), rng);
        List<PlanningContext.Candidate> candidates = new ArrayList<>();
        List<Objective.Listing> rulePlan = new ArrayList<>();
        for (StockSelector.Pick p : picks) {
            candidates.add(new PlanningContext.Candidate(p.item().itemId(), p.item().name(), p.item().perBundle(), p.item().maxBundles(),
                    p.pricePerBundle()));
            rulePlan.add(new Objective.Listing(p.item().itemId(), p.bundles(), p.item().perBundle(), p.pricePerBundle()));
        }
        for (CatalogItem c : alternatives) {
            candidates.add(new PlanningContext.Candidate(c.itemId(), c.name(), c.perBundle(), c.maxBundles(),
                    MarketPricing.bundlePrice(market, c, rng)));
        }
        String title = profile.shopTitles().get(rng.nextInt(profile.shopTitles().size()));
        String sales = salesParts.isEmpty() ? "nothing listed last time" : String.join("; ", salesParts);
        PlanningContext.ShopContext shop = new PlanningContext.ShopContext(profile.roomMapId(), candidates, rulePlan, title, sales,
                config.maxListings());
        PlanningContext ctx = new PlanningContext(PlanningContext.Role.RESIDENT, profile.name(),
                new PlanningContext.Persona(profile.traits(), profile.focus(), profile.blurb()),
                stats[1], profile.jobLine(), stats[0], stats[2], stats[4], stats[5], stats[6], stats[7], stats[3],
                Map.copyOf(stock), memory.recent(5), shop);
        log.info("Resident {} assessed: {} catalog items, stock {} kinds, sales: {}", profile.name(), catalog.size(), stock.size(), sales);
        return new ShopAssessment(profile, charId, market, catalog, stock, sold, ctx, now);
    }

    private static String fmt(double m) {
        return String.format("%.2f", m);
    }

    private static List<Pair<Item, InventoryType>> loadItems(ItemFactory factory, int charId) {
        try {
            return factory.loadItems(charId, false);
        } catch (SQLException e) {
            log.warn("Couldn't load {} items for character {}", factory, charId, e);
            return List.of();
        }
    }

    private static void addItems(Map<Integer, Integer> into, List<Pair<Item, InventoryType>> items) {
        for (Pair<Item, InventoryType> p : items) {
            if (p.getRight() == InventoryType.EQUIPPED) {
                continue;
            }
            into.merge(p.getLeft().getItemId(), (int) p.getLeft().getQuantity(), Integer::sum);
        }
    }

    /** level, job, map, meso, hp, maxhp, mp, maxmp from the character row. */
    private static void readCharacter(int charId, int[] into) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT level, job, map, meso, hp, maxhp, mp, maxmp FROM characters WHERE id = ?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (int i = 0; i < 8; i++) {
                        into[i] = rs.getInt(i + 1);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Couldn't read character {}", charId, e);
        }
    }
}
