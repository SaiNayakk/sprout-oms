package app.sprout.oms.domain;

import app.sprout.oms.domain.Charges.Breakdown;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What the order service's books say, for the services that report on them: every execution (for
 * contract notes, statements and P&amp;L) and what each customer should have (for reconciliation).
 */
@Service
public class Records {

    public record Execution(UUID orderId, UUID userId, UUID tradeId, LocalDate tradeDate, Instant filledAt, String symbol, String side,
                            String product, long quantity, long pricePaise, Breakdown charges, Long realisedPnlPaise, boolean autoSquareOff,
                            String tag) {}

    /** What one customer should have: money held, sale proceeds not yet settled, delivered shares by symbol. */
    public record Expected(UUID userId, long held, long unsettled, Map<String, Long> delivered) {}

    private final JdbcClient db;

    public Records(JdbcClient db) {
        this.db = db;
    }

    public List<Execution> executions(LocalDate from, LocalDate to, UUID userId) {
        if (to.isBefore(from) || from.plusYears(1).isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from must be on or before to, and at most a year before it.");
        }
        return db.sql("""
                        SELECT id, user_id, trade_id, trade_date, filled_at, symbol, side, product, quantity, fill_price_paise, brokerage_paise,
                               stt_paise, exchange_paise, sebi_paise, stamp_paise, gst_paise, realised_pnl_paise, auto_square_off, tag
                        FROM orders WHERE status = 'FILLED' AND trade_date BETWEEN ? AND ? AND (CAST(? AS uuid) IS NULL OR user_id = ?)
                        ORDER BY trade_date, filled_at, id""")
                .params(from, to, userId, userId)
                .query((rs, n) -> new Execution(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, LocalDate.class), rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getLong(9), rs.getLong(10), new Breakdown(rs.getLong(11), rs.getLong(12), rs.getLong(13), rs.getLong(14),
                        rs.getLong(15), rs.getLong(16)), rs.getObject(17, Long.class), rs.getBoolean(18), rs.getString(19)))
                .list();
    }

    public List<Expected> expected() {
        Map<UUID, long[]> money = new LinkedHashMap<>();   // user -> [held, unsettled]
        db.sql("""
                        SELECT user_id, SUM(paise) FROM (
                            SELECT user_id, blocked_paise AS paise FROM orders WHERE blocked_paise > 0
                            UNION ALL SELECT user_id, margin_paise FROM positions WHERE margin_paise > 0) x
                        GROUP BY user_id""")
                .query((rs, n) -> money.computeIfAbsent(rs.getObject(1, UUID.class), k -> new long[2])[0] = rs.getLong(2)).list();
        db.sql("""
                        SELECT user_id, SUM(unsettled_paise) FROM orders
                        WHERE status = 'FILLED' AND unsettled_paise > 0 AND trade_date NOT IN (SELECT trade_date FROM settled_days)
                        GROUP BY user_id""")
                .query((rs, n) -> money.computeIfAbsent(rs.getObject(1, UUID.class), k -> new long[2])[1] = rs.getLong(2)).list();
        Map<UUID, Map<String, Long>> delivered = new LinkedHashMap<>();
        // What the depository holds: the delivered shares, plus those sold on days that haven't settled (they leave it when
        // their day does), but only as many as were delivered: a sale that ate into shares still in transit never touched it.
        db.sql("""
                        WITH unsettled AS (
                            SELECT user_id, symbol,
                                   SUM(CASE WHEN side = 'SELL' THEN quantity ELSE 0 END) AS sold,
                                   SUM(CASE WHEN side = 'BUY' THEN quantity ELSE 0 END) AS bought
                            FROM orders
                            WHERE status = 'FILLED' AND product = 'CNC' AND trade_date IS NOT NULL
                              AND trade_date NOT IN (SELECT trade_date FROM settled_days)
                            GROUP BY user_id, symbol),
                        held AS (
                            SELECT h.user_id, h.symbol,
                                   h.quantity - h.t1_quantity + LEAST(COALESCE(u.sold, 0),
                                       GREATEST(0, h.quantity + COALESCE(u.sold, 0) - COALESCE(u.bought, 0))) AS shares
                            FROM holdings h LEFT JOIN unsettled u ON u.user_id = h.user_id AND u.symbol = h.symbol)
                        SELECT user_id, symbol, shares FROM held WHERE shares <> 0 ORDER BY user_id, symbol""")
                .query((rs, n) -> delivered.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getLong(3)))
                .list();
        Set<UUID> users = new LinkedHashSet<>(money.keySet());
        users.addAll(delivered.keySet());
        List<Expected> out = new ArrayList<>();
        for (UUID u : users) {
            long[] m = money.getOrDefault(u, new long[2]);
            out.add(new Expected(u, m[0], m[1], delivered.getOrDefault(u, Map.of())));
        }
        return out;
    }
}
