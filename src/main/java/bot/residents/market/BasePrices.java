package bot.residents.market;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fair unit price for an item before any market drift, from the server's own data.
 *
 * <p>An NPC shop price, where one exists, is the anchor: nobody pays a player more than the NPC asks,
 * and potions in v83 shops sat right around it. Everything else is drop-only, where the only price in
 * the WZ data is what an NPC pays <em>back</em> ({@code info/price}), far below what players traded at,
 * so those use per-category multipliers and tables. The numbers are deliberately conservative - the
 * aim is a believable market, not an authentic 2010 price list.
 */
public final class BasePrices {
    private static final Pattern RATE = Pattern.compile("(\\d{1,3})%");

    private BasePrices() {
    }

    /**
     * @param npcShopPrice lowest price any NPC shop sells it for, or a non-positive number if none does
     * @param sellBackPrice {@code info/price} from the item data, or a non-positive number if absent
     */
    public static int unitPrice(Speciality speciality, String name, int reqLevel, int npcShopPrice, int sellBackPrice) {
        if (npcShopPrice > 0) {
            return npcShopPrice;
        }
        int sellBack = Math.max(0, sellBackPrice);
        return switch (speciality) {
            case POTIONS -> Math.max(sellBack * 2, 20);
            case SCROLLS -> scroll(name);
            case EQUIPS -> Math.max(1_000 + 30 * reqLevel * reqLevel, sellBack * 3);
            case ORES -> Math.max(sellBack * 5, 200);
        };
    }

    /** By success rate, read from the scroll's name ("... 60%"): the rarer outcomes cost more, except 10%. */
    static int scroll(String name) {
        int rate = successRate(name);
        int base = switch (rate) {
            case 100 -> 6_000;
            case 70 -> 40_000;
            case 60 -> 25_000;
            case 30 -> 55_000;
            case 10 -> 10_000;
            default -> 15_000;
        };
        boolean attack = name != null && name.toLowerCase().contains("att");
        return attack ? base * 2 : base;
    }

    static int successRate(String name) {
        if (name == null) {
            return -1;
        }
        Matcher m = RATE.matcher(name);
        int rate = -1;
        while (m.find()) {
            rate = Integer.parseInt(m.group(1));
        }
        return rate;
    }

    /** Prices a player would type: exact below 100, then to the nearest 5, 50, 500. Never below 1. */
    public static int roundNice(double price) {
        long p = Math.round(price);
        if (p < 100) {
            return (int) Math.max(1, p);
        }
        long step = p < 1_000 ? 5 : p < 10_000 ? 50 : 500;
        long rounded = Math.round((double) p / step) * step;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(step, rounded));
    }
}
