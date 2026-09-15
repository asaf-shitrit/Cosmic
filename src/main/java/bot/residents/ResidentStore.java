package bot.residents;

import bot.llm.Json;
import bot.residents.market.MarketPricing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code resident_state}: a resident's market memory between wakes. Deliberately the only thing
 * stored - stock, mesos and the shop itself are read from where the game keeps them.
 */
final class ResidentStore {
    private static final Logger log = LoggerFactory.getLogger(ResidentStore.class);

    private ResidentStore() {
    }

    static MarketPricing.State loadMarket(String name) {
        MarketPricing.State state = new MarketPricing.State();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT market_json FROM resident_state WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    decode(rs.getString(1), state);
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.warn("Couldn't load market state for resident {}; starting from base prices", name, e);
        }
        return state;
    }

    static void saveMarket(String name, MarketPricing.State state) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO resident_state (name, market_json) VALUES (?, ?) "
                     + "ON DUPLICATE KEY UPDATE market_json = VALUES(market_json)")) {
            ps.setString(1, name);
            ps.setString(2, encode(state));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Couldn't save market state for resident {}", name, e);
        }
    }

    static String encode(MarketPricing.State state) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        state.multipliers().forEach((id, v) -> m.put(String.valueOf(id), Math.round(v * 10_000) / 10_000.0));
        Map<String, Object> listed = new LinkedHashMap<>();
        state.listed().forEach((id, v) -> listed.put(String.valueOf(id), v));
        root.put("multipliers", m);
        root.put("listed", listed);
        return Json.write(root);
    }

    static void decode(String json, MarketPricing.State into) {
        Map<String, Object> root = Json.asObject(Json.parse(json), "market");
        if (root.get("multipliers") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> into.setMultiplier(Integer.parseInt(String.valueOf(k)), Json.asDouble(v, "multiplier")));
        }
        if (root.get("listed") instanceof Map<?, ?> l) {
            l.forEach((k, v) -> into.setListedUnits(Integer.parseInt(String.valueOf(k)), (int) Json.asLong(v, "listed")));
        }
    }
}
