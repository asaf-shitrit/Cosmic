package bot.party;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;


/**
 * Human names for summoned companions.
 *
 * <p>A companion's account keeps the machine-readable name ({@code Shu<ownerId>b<slot>}) because nothing
 * a player sees should have to encode that, and the account is what the server-side bookkeeping looks
 * bots up by. The <em>character</em> is what stands next to you in a party, so its name comes from here:
 * an adventurer's name, not a serial number.
 *
 * <p>Names are drawn from a fixed pool rather than generated from syllables, because a generated name
 * reads generated ("Sharil32") and the pool is small enough to read through. The pick is
 * <em>deterministic</em> in (owner, slot) so an owner's third companion is always the same person across
 * sessions; the character keeps that name for good once created, and this only decides the first one.
 *
 * <p>Every candidate has to pass {@code Character.canCreateChar}, which is the server's own check:
 * names containing a blocked substring are refused there <em>silently</em> - no error packet, no
 * disconnect, the client just waits. {@code CompanionNamesTest} runs the whole pool through that method
 * so a name can never be added here that the server would refuse.
 */
final class CompanionNames {
    /**
     * Adventurer names, all 4-8 letters, none matching the server's blocked substrings. Deliberately
     * ordinary: a companion is meant to look like someone who happens to be training nearby.
     */
    static final String[] POOL = {
            "Alder", "Ansel", "Bram", "Brin", "Cass", "Cora", "Corvin", "Daria", "Doren", "Elowen",
            "Ember", "Fenn", "Fenwick", "Freya", "Garrick", "Gwen", "Halden", "Hesper", "Hilda", "Ines",
            "Iris", "Ivo", "Jory", "Junia", "Kael", "Kellen", "Kestrel", "Kira", "Lira", "Lorne",
            "Lysa", "Marek", "Merrick", "Mira", "Nadia", "Nessa", "Nia", "Orin", "Orlan", "Osric",
            "Pell", "Perrin", "Petra", "Quill", "Rook", "Rowena", "Rune", "Sable", "Selby", "Sorrel",
            "Tamsin", "Teague", "Tobin", "Ulric", "Una", "Vance", "Vessa", "Vesta", "Wendel", "Wilma",
            "Wren", "Xara", "Yara", "Yorin", "Ysolde", "Zara", "Zeke", "Zora",
    };

    private CompanionNames() {
    }

    /**
     * The name this owner's slot wants, whether or not it is free.
     *
     * <p>Package-private for the test: the property that matters is that the same pair always yields the
     * same name, so a companion is the same person every time it is summoned.
     */
    static String wanted(int ownerCharId, int slot) {
        return POOL[Math.floorMod(Integer.hashCode(ownerCharId * 31 + slot), POOL.length)];
    }

    /**
     * The wanted name, or the next free one after it.
     *
     * <p>Two owners can want the same name, and a character name is unique across the whole server -
     * a duplicate would be refused at creation, and refused in the same silent way a blocked name is.
     * Probing forward keeps the pick deterministic while still making the collision disappear.
     *
     * @param taken answers "is this character name already in use?" - the database in production, a set
     *              in tests
     * @return a free name, or null if the pool has nothing left (the caller falls back to the account name)
     */
    static String available(int ownerCharId, int slot, NameLookup taken) throws SQLException {
        int start = Math.floorMod(Integer.hashCode(ownerCharId * 31 + slot), POOL.length);
        for (int probe = 0; probe < POOL.length; probe++) {
            String candidate = POOL[(start + probe) % POOL.length];
            if (!taken.isTaken(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Asks the database whether a character name is in use. */
    interface NameLookup {
        boolean isTaken(String name) throws SQLException;
    }

    /** The production lookup: character names are unique across the whole database. */
    static NameLookup databaseLookup(Connection con) {
        return name -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM characters WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        };
    }

    /** Every name currently in use, for callers that would otherwise query once per probe. */
    static Set<String> takenNames(Connection con) throws SQLException {
        Set<String> taken = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT name FROM characters")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    taken.add(rs.getString(1).toLowerCase());
                }
            }
        }
        return taken;
    }
}
