package bot.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link UsageLedger.CounterStore} over the {@code bot_usage} table (created by the
 * {@code db/extensions} changelog). A database error reads as "fully used" rather than "unused":
 * when the ledger can't be read, a cap must fail closed, never hand out unlimited LLM calls or mesos.
 */
public final class DbCounterStore implements UsageLedger.CounterStore {
    private static final Logger log = LoggerFactory.getLogger(DbCounterStore.class);

    @Override
    public long get(String counter, String windowId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT amount FROM bot_usage WHERE counter = ? AND window_id = ?")) {
            ps.setString(1, counter);
            ps.setString(2, windowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Couldn't read usage counter {} [{}]; treating it as exhausted", counter, windowId, e);
            return Long.MAX_VALUE / 2;
        }
    }

    @Override
    public void add(String counter, String windowId, long delta) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO bot_usage (counter, window_id, amount) VALUES (?, ?, ?) "
                     + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)")) {
            ps.setString(1, counter);
            ps.setString(2, windowId);
            ps.setLong(3, delta);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Couldn't update usage counter {} [{}] by {}", counter, windowId, delta, e);
        }
    }
}
