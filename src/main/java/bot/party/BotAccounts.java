package bot.party;

import tools.BCrypt;
import tools.DatabaseConnection;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Names and credentials for summoned bots.
 *
 * <p>A bot logs in exactly like a player, so its account needs a password - but nobody should be able
 * to log into a bot account from a real client and puppet the character. So there is no fixed
 * password: each session gets a fresh random one, written as a bcrypt hash straight to the account
 * row before the bot logs in, and never stored anywhere else. A brand-new account doesn't exist yet;
 * the login server's own {@code AUTOMATIC_REGISTER} creates it from that first login.
 */
final class BotAccounts {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Account and character names share a limit: over 12 characters the insert throws MysqlDataTruncation. */
    static final int MAX_NAME_LENGTH = 12;

    private BotAccounts() {
    }

    /**
     * {@code "Shu" + ownerCharId + "b" + (slot + 1)}, e.g. {@code Shu1234b1}. Deterministic, so a
     * player's bots keep the same characters from one summon to the next, and alphanumeric, which
     * {@code Character.canCreateChar} requires. Owner ids up to 7 digits fit in 12 characters. Letters
     * are only "Shu" and "b" around digits, so no name can contain an entry of the substring
     * blocklist {@code Character.BLOCKED_NAMES} (a blocked name gets no reply at all and the login
     * would hang until the supervisor's login deadline).
     *
     * @return the name, or {@code null} if this owner's id is too long to fit
     */
    /**
     * The <em>account</em> name for one of an owner's companion slots: {@code Shu<ownerId>b<slot+1>}.
     *
     * <p>Machine-readable on purpose, and invisible to players - it is what the server-side bookkeeping
     * looks a companion up by, while the character standing in the world gets a human name from
     * {@link CompanionNames}. Encoding the slot here is what makes a companion the same character (and
     * the same person) every time it is summoned.
     */
    static String botAccountName(int ownerCharId, int slot) {
        String name = "Shu" + ownerCharId + "b" + (slot + 1);
        return name.length() <= MAX_NAME_LENGTH ? name : null;
    }

    /** The slot a bot account name encodes, or -1 if this is not one. */
    static int slotOfBotAccount(String accountName) {
        if (accountName == null || !accountName.startsWith("Shu")) {
            return -1;
        }
        int b = accountName.lastIndexOf('b');
        if (b < 0 || b + 1 >= accountName.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(accountName.substring(b + 1)) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Issues the password for one session. If the account already exists it must look like a bot
     * account - no characters, or a single character bearing the account's own name - before its
     * password is replaced, so that a player who happened to register this name keeps their account.
     *
     * @throws IllegalStateException if the name belongs to something that isn't a bot account
     */
    static String issueSessionPassword(String accountName) throws SQLException {
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        String password = HexFormat.of().formatHex(raw);

        try (Connection con = DatabaseConnection.getConnection()) {
            int accountId = -1;
            try (PreparedStatement ps = con.prepareStatement("SELECT id FROM accounts WHERE name = ?")) {
                ps.setString(1, accountName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        accountId = rs.getInt(1);
                    }
                }
            }
            if (accountId == -1) {
                return password;       // AUTOMATIC_REGISTER creates it with this password on first login
            }

            try (PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE accountid = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                    }
                    // A bot account holds at most one character. It used to have to hold one whose name
                    // matched the account's, which stopped being true when companions started getting
                    // human names - a character name is for players to read, the account name is for
                    // lookups. Two characters on the account would still mean it is not ours to reuse.
                    if (count > 1) {
                        throw new IllegalStateException("account " + accountName + " is not a bot account");
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET password = ? WHERE id = ?")) {
                ps.setString(1, BCrypt.hashpw(password, BCrypt.gensalt(12)));
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        }
        return password;
    }
}
