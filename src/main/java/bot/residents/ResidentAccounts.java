package bot.residents;

import tools.BCrypt;
import tools.DatabaseConnection;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Credentials for resident accounts, on the same rule as {@code bot.party.BotAccounts} (package-private
 * there, so not reusable before the merge): no fixed password, a fresh random one per session written
 * as a bcrypt hash just before login, so nobody can log into a resident from a real client. A missing
 * account is created by the login server's own {@code AUTOMATIC_REGISTER} on that first login.
 */
final class ResidentAccounts {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ResidentAccounts() {
    }

    /**
     * @throws IllegalStateException if an account with this name exists but holds any character other
     *                               than the resident itself - a player who registered the name keeps it
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
                return password;
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE accountid = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    boolean foreign = false;
                    while (rs.next()) {
                        count++;
                        foreign |= !rs.getString(1).equalsIgnoreCase(accountName);
                    }
                    if (count > 1 || foreign) {
                        throw new IllegalStateException("account " + accountName + " is not a resident account");
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
