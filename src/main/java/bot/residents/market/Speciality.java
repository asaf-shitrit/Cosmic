package bot.residents.market;

/** What a resident trades. Each maps onto a slice of the server's own item data, see {@code ItemCatalog}. */
public enum Speciality {
    /** HP/MP potions and food from NPC shops. */
    POTIONS,
    /** Equipment scrolls that monsters drop. */
    SCROLLS,
    /** Monster-dropped equipment within the resident's level band. */
    EQUIPS,
    /** Mineral and jewel ores, plates and crystals: crafting materials. */
    ORES
}
