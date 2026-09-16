package bot.combat;

/** How far a companion may range while choosing or attacking a monster. */
public enum CombatScope {
    /** Ordinary field assistance stays near the human owner. */
    NEAR_OWNER,
    /** An active party-quest stage may use the whole current instance map. */
    FULL_MAP
}
