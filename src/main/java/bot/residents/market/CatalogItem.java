package bot.residents.market;

/**
 * One item a resident may stock, with what the server's own data says about it.
 *
 * @param basePrice  fair mesos for one unit (see {@link BasePrices})
 * @param perBundle  units in one shop bundle (potions and ores sell in stacks, equips and scrolls singly)
 * @param maxBundles most bundles of it one shop lists at once
 * @param reqLevel   the level the item is for: an equip's {@code reqLevel}, or a band estimate for consumables
 */
public record CatalogItem(int itemId, String name, Speciality speciality, int reqLevel, int basePrice, int perBundle,
                          int maxBundles) {
}
