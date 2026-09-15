package bot.residents.market;

import bot.residents.ResidentProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What residents may stock, derived once from the server's own data - never from a web list, so
 * nothing post-Big-Bang can appear:
 *
 * <ul>
 *   <li><b>Potions</b>: consumables a regular town NPC shop sells ({@code shopitems}), HP/MP potions,
 *       pills and food up to 5000 mesos, priced at the lowest NPC price, level-banded by that price.</li>
 *   <li><b>Scrolls</b>: equipment scrolls ({@code 2040000-2048999}) that some monster drops
 *       ({@code drop_data}/{@code drop_data_global}).</li>
 *   <li><b>Equips</b>: monster-dropped equipment whose WZ {@code reqLevel} is inside the resident's band.</li>
 *   <li><b>Ores</b>: mineral/jewel ores and their plates, and the crystal ores, as they exist in {@code Item.wz}.</li>
 * </ul>
 *
 * Every candidate must have a name in {@code String.wz}, and must not be a cash item, a quest item,
 * trade-blocked or unmerchable - a Hired Merchant would refuse it.
 */
public final class ItemCatalog {
    private static final Logger log = LoggerFactory.getLogger(ItemCatalog.class);

    private static volatile ItemCatalog instance;

    private final Map<Integer, Integer> npcPrices;
    private final Set<Integer> dropped;
    private final Map<Speciality, List<CatalogItem>> bySpeciality = new HashMap<>();

    private ItemCatalog(Map<Integer, Integer> npcPrices, Set<Integer> dropped) {
        this.npcPrices = npcPrices;
        this.dropped = dropped;
    }

    public static ItemCatalog get() {
        ItemCatalog c = instance;
        if (c == null) {
            synchronized (ItemCatalog.class) {
                if (instance == null) {
                    instance = load();
                }
                c = instance;
            }
        }
        return c;
    }

    private static ItemCatalog load() {
        Map<Integer, Integer> npc = new HashMap<>();
        Set<Integer> dropped = new TreeSet<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            // Only shops of NPCs below 9000000: the Victoria Island and Ossyria regulars. The 9xxxxxx
            // NPCs are world-tour, event and regional shops (Singapore/Malaysia hawker food and the like)
            // that would make an FM potion stall look like an import store.
            try (PreparedStatement ps = con.prepareStatement("SELECT si.itemid, MIN(si.price) FROM shopitems si "
                    + "JOIN shops s ON s.shopid = si.shopid WHERE si.price > 0 AND s.npcid < 9000000 GROUP BY si.itemid");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    npc.put(rs.getInt(1), rs.getInt(2));
                }
            }
            for (String table : List.of("drop_data", "drop_data_global")) {
                try (PreparedStatement ps = con.prepareStatement("SELECT DISTINCT itemid FROM " + table + " WHERE questid = 0");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        dropped.add(rs.getInt(1));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Couldn't read shop and drop tables for the resident catalog", e);
        }
        log.info("Resident item catalog: {} NPC-shop items, {} droppable items", npc.size(), dropped.size());
        return new ItemCatalog(Map.copyOf(npc), Set.copyOf(dropped));
    }

    /** Candidates for one resident: its speciality, inside its level band. */
    public List<CatalogItem> forProfile(ResidentProfile profile) {
        return all(profile.speciality()).stream()
                // Ores and scrolls carry no level (0): every band may sell them.
                .filter(c -> c.reqLevel() == 0 || (c.reqLevel() >= profile.bandMin() && c.reqLevel() <= profile.bandMax()))
                .toList();
    }

    /** The catalog entry for {@code itemId} within {@code speciality}, if it is one. */
    public CatalogItem find(Speciality speciality, int itemId) {
        return all(speciality).stream().filter(c -> c.itemId() == itemId).findFirst().orElse(null);
    }

    private synchronized List<CatalogItem> all(Speciality s) {
        return bySpeciality.computeIfAbsent(s, this::build);
    }

    private List<CatalogItem> build(Speciality s) {
        List<CatalogItem> items = new ArrayList<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        switch (s) {
            case POTIONS -> npcPrices.forEach((id, price) -> {
                if ((inRange(id, 2000000, 2002999) || inRange(id, 2010000, 2012999) || inRange(id, 2020000, 2022999)) && price <= 5000) {
                    int perBundle = price < 200 ? 50 : price < 1000 ? 20 : 10;
                    add(items, ii, s, id, potionLevel(price), perBundle);
                }
            });
            case SCROLLS -> dropped.forEach(id -> {
                if (inRange(id, 2040000, 2048999)) {
                    add(items, ii, s, id, 0, 1);
                }
            });
            case EQUIPS -> dropped.forEach(id -> {
                if (inRange(id, 1000000, 1999999)) {
                    Integer req = ii.getEquipLevelReq(id);
                    add(items, ii, s, id, req == null ? 0 : req, 1);
                }
            });
            case ORES -> {
                for (int[] range : new int[][]{{4010000, 4010007, 10}, {4020000, 4020008, 10}, {4011000, 4011006, 5},
                        {4021000, 4021008, 5}, {4004000, 4004004, 1}}) {
                    for (int id = range[0]; id <= range[1]; id++) {
                        add(items, ii, s, id, 0, range[2]);
                    }
                }
            }
        }
        items.sort(java.util.Comparator.comparingInt(CatalogItem::itemId));
        log.info("Resident catalog for {}: {} items", s, items.size());
        return List.copyOf(items);
    }

    private void add(List<CatalogItem> out, ItemInformationProvider ii, Speciality s, int itemId, int reqLevel, int perBundle) {
        String name = ii.getName(itemId);
        if (name == null || name.isBlank() || ii.isCash(itemId) || ii.isQuestItem(itemId) || ii.isLootRestricted(itemId)
                || ii.isUnmerchable(itemId)) {
            return;
        }
        int slotMax = ii.getSlotMax(null, itemId);   // null client is safe: only stars and bullets consult it
        if (slotMax <= 0) {
            return;
        }
        int bundle = Math.min(perBundle, slotMax);
        int maxBundles = Math.max(1, Math.min(s == Speciality.EQUIPS ? 1 : 5, slotMax / bundle));
        int base = BasePrices.unitPrice(s, name, reqLevel, npcPrices.getOrDefault(itemId, -1), ii.getWholePrice(itemId));
        out.add(new CatalogItem(itemId, name, s, reqLevel, base, bundle, maxBundles));
    }

    /** Price is the best level proxy a consumable has: the pricier the potion, the later players buy it. */
    static int potionLevel(int npcPrice) {
        if (npcPrice <= 100) {
            return 1;
        }
        if (npcPrice <= 400) {
            return 20;
        }
        return npcPrice <= 1000 ? 40 : 60;
    }

    private static boolean inRange(int id, int lo, int hi) {
        return id >= lo && id <= hi;
    }
}
