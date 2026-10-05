package app.sprout.oms.domain;

import static app.sprout.oms.domain.LedgerOutbox.cash;
import static app.sprout.oms.domain.LedgerOutbox.dues;
import static app.sprout.oms.domain.LedgerOutbox.hold;
import static app.sprout.oms.domain.LedgerOutbox.unsettled;

import app.sprout.oms.domain.Upstreams.Quote;
import app.sprout.oms.domain.Upstreams.Unreachable;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** What a customer owns and owes, valued at the latest prices when market data can be reached. */
@Service
public class Portfolio {

    public record Holding(String symbol, long quantity, long t1Quantity, long cost) {}

    public record Position(String symbol, LocalDate session, long quantity, long cost, long margin, long realisedPnl) {}

    private final JdbcClient db;
    private final Upstreams up;

    public Portfolio(JdbcClient db, Upstreams up) {
        this.db = db;
        this.up = up;
    }

    public Map<String, Object> funds(UUID user) {
        up.requireAccount(user);
        try {
            long cash = up.balance(cash(user));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("availableToTrade", Money.rupees(cash));
            m.put("cash", Money.rupees(cash));
            m.put("blocked", Money.rupees(up.balance(hold(user))));
            m.put("unsettled", Money.rupees(up.balance(unsettled(user))));
            m.put("dues", Money.rupees(up.balance(dues(user))));
            return m;
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
    }

    public List<Map<String, Object>> holdings(UUID user) {
        List<Holding> rows = db.sql("SELECT symbol, quantity, t1_quantity, cost_paise FROM holdings WHERE user_id = ? AND quantity > 0 ORDER BY symbol")
                .param(user).query((rs, n) -> new Holding(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).list();
        Map<String, Quote> quotes = quotes(rows.stream().map(Holding::symbol).toList());
        return rows.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", h.symbol());
            m.put("quantity", h.quantity());
            m.put("t1Quantity", h.t1Quantity());
            m.put("averagePrice", Money.rupees(Math.round((double) h.cost() / h.quantity())));
            m.put("investedValue", Money.rupees(h.cost()));
            Quote q = quotes.get(h.symbol());
            if (q != null) {
                long value = q.lastPaise() * h.quantity();
                m.put("lastPrice", Money.rupees(q.lastPaise()));
                m.put("currentValue", Money.rupees(value));
                m.put("pnl", Money.rupees(value - h.cost()));
            }
            return m;
        }).toList();
    }

    public List<Map<String, Object>> positions(UUID user) {
        LocalDate latest = db.sql("SELECT MAX(session_date) FROM positions WHERE user_id = ?").param(user)
                .query(LocalDate.class).optional().orElse(null);
        if (latest == null) {
            return List.of();
        }
        List<Position> rows = db.sql("""
                        SELECT symbol, session_date, quantity, cost_paise, margin_paise, realised_pnl_paise FROM positions
                        WHERE user_id = ? AND (session_date = ? OR quantity <> 0) ORDER BY session_date DESC, symbol""")
                .params(user, latest)
                .query((rs, n) -> new Position(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6)))
                .list();
        Map<String, Quote> quotes = quotes(rows.stream().filter(p -> p.quantity() != 0).map(Position::symbol).distinct().toList());
        return rows.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", p.symbol());
            m.put("product", "MIS");
            m.put("quantity", p.quantity());
            if (p.quantity() != 0) {
                m.put("averagePrice", Money.rupees(Math.round((double) p.cost() / Math.abs(p.quantity()))));
            }
            m.put("margin", Money.rupees(p.margin()));
            Quote q = quotes.get(p.symbol());
            if (q != null && p.quantity() != 0) {
                long value = q.lastPaise() * Math.abs(p.quantity());
                m.put("lastPrice", Money.rupees(q.lastPaise()));
                m.put("unrealisedPnl", Money.rupees(p.quantity() > 0 ? value - p.cost() : p.cost() - value));
            }
            m.put("realisedPnl", Money.rupees(p.realisedPnl()));
            m.put("sessionDate", p.session().toString());
            return m;
        }).toList();
    }

    private Map<String, Quote> quotes(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        try {
            return up.quotes(symbols);
        } catch (Unreachable e) {
            return Map.of();   // shown without today's prices
        }
    }
}
