package app.sprout.oms.domain;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * An order's way through Sprout, step by step, from what each step recorded: placed here, money held in the
 * ledger, sent to and matched on the exchange, the holdings updated, and the fill posted to the ledger (from the
 * outbox, which notes when the ledger took it). Nothing is recorded for the trace alone. The demo's pipeline view
 * polls it while an order is on its way.
 */
@Service
public class Traces {

    public record Step(String service, String step, Instant at, String status, String detail) {}

    private record Row(String status, String side, int quantity, String symbol, long holdPaise, Long fillPaise, String rejection,
                       Instant created, Instant sent, Instant filled, Instant updated) {}

    private final JdbcClient db;

    public Traces(JdbcClient db) {
        this.db = db;
    }

    public Map<String, Object> trace(UUID user, UUID id) {
        Row o = db.sql("""
                        SELECT status, side, quantity, symbol, hold_paise, fill_price_paise, rejection_message, created_at, sent_at, filled_at, updated_at
                        FROM orders WHERE id = ? AND user_id = ?""")
                .params(id, user)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5),
                        (Long) rs.getObject(6), rs.getString(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)),
                        instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11))))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such order of yours."));

        List<Step> steps = new ArrayList<>();
        steps.add(new Step("oms", "Order placed: " + o.side().toLowerCase() + " " + o.quantity() + " " + o.symbol(), o.created(), "ok", null));
        if (o.holdPaise() > 0) {
            // held in the same request that placed it, before the order was saved
            steps.add(new Step("ledger", "₹" + Money.rupees(o.holdPaise()) + " held for it", o.created(), "ok", null));
        }
        boolean complete = switch (o.status()) {
            case "AMO_QUEUED" -> {
                steps.add(new Step("oms", "Queued until the market opens", o.created(), "pending", null));
                yield false;
            }
            case "REJECTED" -> {
                steps.add(new Step(o.sent() == null ? "oms" : "exchange", "Rejected", o.updated(), "failed", o.rejection()));
                yield true;
            }
            case "CANCELLED", "EXPIRED" -> {
                sent(steps, o);
                steps.add(new Step("oms", o.status().equals("CANCELLED") ? "Cancelled: the money held is back" : "Expired: the money held is back",
                        o.updated(), "ok", null));
                yield true;
            }
            case "FILLED" -> {
                sent(steps, o);
                steps.add(new Step("exchange", "Matched at ₹" + Money.rupees(o.fillPaise()), o.filled(), "ok", null));
                steps.add(new Step("portfolio", "Holdings updated", o.filled(), "ok", null));
                Optional<Instant> posted = db.sql("SELECT posted_at FROM ledger_outbox WHERE key = ?").param("fill:" + id)
                        .query((rs, n) -> Optional.ofNullable(instant(rs.getTimestamp(1)))).optional().flatMap(p -> p);
                if (posted.isPresent()) {
                    steps.add(new Step("ledger", "Trade posted to the ledger", posted.get(), "ok", null));
                    yield true;
                }
                steps.add(new Step("ledger", "Posting the trade to the ledger", o.filled(), "pending", null));
                yield false;
            }
            default -> {   // PENDING, OPEN
                sent(steps, o);
                steps.add(new Step("exchange", o.sent() == null ? "Sending to the exchange" : "Waiting for a match", o.sent() == null ? o.created() : o.sent(),
                        "pending", null));
                yield false;
            }
        };

        steps.sort(Comparator.comparing(Step::at));   // stable: steps at the same moment keep their order
        List<Map<String, Object>> out = new ArrayList<>();
        Instant before = null;
        for (Step s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", s.service());
            m.put("step", s.step());
            m.put("at", s.at().toString());
            if (before != null) {
                m.put("durationMs", Duration.between(before, s.at()).toMillis());
            }
            m.put("status", s.status());
            if (s.detail() != null) {
                m.put("detail", s.detail());
            }
            out.add(m);
            before = s.at();
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("orderId", id.toString());
        trace.put("complete", complete);
        trace.put("steps", out);
        return trace;
    }

    private static void sent(List<Step> steps, Row o) {
        if (o.sent() != null) {
            steps.add(new Step("exchange", "Sent to the exchange", o.sent(), "ok", null));
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
