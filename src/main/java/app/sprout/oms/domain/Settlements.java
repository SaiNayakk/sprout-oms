package app.sprout.oms.domain;

import static app.sprout.oms.domain.LedgerOutbox.cash;
import static app.sprout.oms.domain.LedgerOutbox.dues;
import static app.sprout.oms.domain.LedgerOutbox.unsettled;

import app.sprout.oms.domain.LedgerOutbox.Entry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order service's part in settling a trade date, called by Sprout's settlement back office:
 * what Sprout's own books say the day comes to (to check the clearing corporation's obligation
 * against), booking the cost of a client's short delivery to them, and, once the day has settled,
 * making each client's sale proceeds cash and their bought shares delivered.
 */
@Service
public class Settlements {

    private static final Logger log = LoggerFactory.getLogger(Settlements.class);

    /** One client's trading in one share on the day. */
    public record Line(UUID userId, String symbol, long bought, long sold) {}

    /**
     * The day by Sprout's books: what it owes the clearing corporation for purchases and intraday
     * losses (and close-outs), what it is owed for sales and intraday profits, and each client's lines.
     * {@code unposted} counts ledger entries not yet booked; the day can't be checked until it is zero.
     */
    public record Summary(LocalDate tradeDate, long payable, long receivable, long closeOuts, int unposted, List<Line> lines) {}

    public record Shortage(UUID userId, String symbol, long quantity, long closeOutPaise) {}

    public record Delivery(UUID userId, String symbol, long quantity) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LedgerOutbox ledger;

    public Settlements(JdbcClient db, TransactionTemplate tx, Clock clock, LedgerOutbox ledger) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.ledger = ledger;
    }

    public Summary summary(LocalDate day) {
        long[] money = db.sql("""
                        SELECT COALESCE(SUM(CASE WHEN product = 'CNC' AND side = 'BUY' THEN fill_price_paise * quantity
                                                 WHEN product = 'MIS' AND realised_pnl_paise < 0 THEN -realised_pnl_paise ELSE 0 END), 0),
                               COALESCE(SUM(CASE WHEN product = 'CNC' AND side = 'SELL' THEN fill_price_paise * quantity
                                                 WHEN product = 'MIS' AND realised_pnl_paise > 0 THEN realised_pnl_paise ELSE 0 END), 0)
                        FROM orders WHERE status = 'FILLED' AND trade_date = ?""")
                .param(day).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        long closeOuts = db.sql("SELECT COALESCE(SUM(paise), 0) FROM close_outs WHERE trade_date = ?").param(day).query(Long.class).single();
        int unposted = db.sql("SELECT COUNT(*) FROM ledger_outbox WHERE posted_at IS NULL").query(Integer.class).single();
        List<Line> lines = db.sql("""
                        SELECT user_id, symbol, COALESCE(SUM(quantity) FILTER (WHERE side = 'BUY'), 0), COALESCE(SUM(quantity) FILTER (WHERE side = 'SELL'), 0)
                        FROM orders WHERE status = 'FILLED' AND trade_date = ? GROUP BY user_id, symbol ORDER BY user_id, symbol""")
                .param(day).query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4))).list();
        return new Summary(day, money[0] + closeOuts, money[1], closeOuts, unposted, lines);
    }

    /** Charges each short delivery's close-out to the client (as dues, recovered from their cash). Repeating it does nothing. */
    public void bookShortages(LocalDate day, String settlementId, List<Shortage> shortages) {
        tx.executeWithoutResult(s -> {
            for (Shortage sh : shortages) {
                int fresh = db.sql("""
                                INSERT INTO close_outs (settlement_id, user_id, symbol, quantity, paise, trade_date, booked_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                        .params(settlementId, sh.userId(), sh.symbol(), sh.quantity(), sh.closeOutPaise(), day, Timestamp.from(clock.instant()))
                        .update();
                if (fresh == 1 && sh.closeOutPaise() > 0) {
                    ledger.add("close-out:" + settlementId + ":" + sh.userId() + ":" + sh.symbol(),
                            "Close-out: " + sh.quantity() + " " + sh.symbol() + " sold on " + day + " weren't delivered", settlementId,
                            new Entry().debit(dues(sh.userId()), sh.closeOutPaise()).credit("sprout:clearing-payable", sh.closeOutPaise()));
                    db.sql("INSERT INTO dues (user_id, since) VALUES (?, ?) ON CONFLICT DO NOTHING").params(sh.userId(), Timestamp.from(clock.instant()))
                            .update();
                    log.warn("Client {} was short {} {} for {}: closed out for ₹{}", sh.userId(), sh.quantity(), sh.symbol(), day,
                            Money.rupees(sh.closeOutPaise()));
                }
            }
        });
        ledger.flush();
    }

    /**
     * The day has settled: each client's sale proceeds become cash they can invest or withdraw, and
     * the shares delivered to their demat account stop being T1. Done once per trade date.
     */
    public boolean complete(LocalDate day, String settlementId, List<Delivery> deliveries) {
        Boolean done = tx.execute(s -> {
            int fresh = db.sql("INSERT INTO settled_days (trade_date, settlement_id, completed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(day, settlementId, Timestamp.from(clock.instant())).update();
            if (fresh == 0) {
                return false;
            }
            List<Object[]> proceeds = db.sql("""
                            SELECT user_id, SUM(unsettled_paise) FROM orders WHERE status = 'FILLED' AND trade_date = ? AND unsettled_paise > 0
                            GROUP BY user_id""")
                    .param(day).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getLong(2)}).list();
            for (Object[] p : proceeds) {
                UUID user = (UUID) p[0];
                long paise = (Long) p[1];
                ledger.add("settle:" + day + ":" + user, "Sales of " + day + " settled: now yours to invest or withdraw", settlementId,
                        new Entry().debit(unsettled(user), paise).credit(cash(user), paise));
            }
            for (Delivery d : deliveries) {
                if (d.quantity() > 0) {
                    db.sql("UPDATE holdings SET t1_quantity = GREATEST(0, t1_quantity - ?) WHERE user_id = ? AND symbol = ?")
                            .params(d.quantity(), d.userId(), d.symbol()).update();
                }
            }
            log.info("Trade date {} settled ({}): proceeds released for {} client(s), {} delivery line(s)", day, settlementId, proceeds.size(),
                    deliveries.size());
            return true;
        });
        ledger.flush();
        return Boolean.TRUE.equals(done);
    }
}
