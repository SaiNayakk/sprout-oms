package app.sprout.oms.domain;

import static app.sprout.oms.domain.LedgerOutbox.cash;
import static app.sprout.oms.domain.LedgerOutbox.dues;

import app.sprout.oms.config.OmsProperties;
import app.sprout.oms.domain.LedgerOutbox.Entry;
import app.sprout.oms.domain.Upstreams.Market;
import app.sprout.oms.domain.Upstreams.Quote;
import app.sprout.oms.domain.Upstreams.Reply;
import app.sprout.oms.domain.Upstreams.Unreachable;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The risk desk, run every couple of seconds: books what the ledger hasn't taken yet, asks the
 * exchange about orders it hasn't confirmed, sends after-market orders at the open, closes intraday
 * positions at square-off time (or early, when their loss eats the margin), and recovers what
 * customers owe from their cash.
 */
@Component
public class Rms {

    private static final Logger log = LoggerFactory.getLogger(Rms.class);

    record OpenPosition(UUID user, String symbol, LocalDate session, long quantity, long cost, long margin) {}

    private final Orders orders;
    private final LedgerOutbox ledger;
    private final Upstreams up;
    private final JdbcClient db;
    private final Clock clock;
    private final OmsProperties props;

    public Rms(Orders orders, LedgerOutbox ledger, Upstreams up, JdbcClient db, Clock clock, OmsProperties props) {
        this.orders = orders;
        this.ledger = ledger;
        this.up = up;
        this.db = db;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${sprout.oms.rms-every:2s}")
    void scheduled() {
        try {
            round();
        } catch (RuntimeException e) {
            log.warn("The risk round didn't finish: {}", e.getMessage());
        }
    }

    public void round() {
        ledger.flush();
        orders.reconcile(clock.instant().minus(props.reconcileAfter()));
        Market m;
        try {
            m = up.market();
        } catch (Unreachable e) {
            return;
        }
        if (m.open()) {
            orders.dispatchAmos(m);
            squareOffs(m);
        }
        recoverDues();
    }

    void squareOffs(Market m) {
        boolean late = !m.time().isBefore(props.squareOffAt());
        if (late) {
            cancelWorkingIntraday(m);
        }
        List<OpenPosition> open = db.sql("""
                        SELECT user_id, symbol, session_date, quantity, cost_paise, margin_paise FROM positions
                        WHERE quantity <> 0 AND session_date <= ?""")
                .param(m.sessionDate())
                .query((rs, n) -> new OpenPosition(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)))
                .list();
        Map<String, Quote> quotes = null;
        for (OpenPosition p : open) {
            if (late || p.session().isBefore(m.sessionDate())) {
                orders.squareOff(p.user(), p.symbol(), p.session(), "Auto square-off: intraday positions close at " + props.squareOffAt() + ".");
                continue;
            }
            if (quotes == null) {
                try {
                    quotes = up.quotes(open.stream().map(OpenPosition::symbol).distinct().toList());
                } catch (Unreachable e) {
                    return;
                }
            }
            Quote q = quotes.get(p.symbol());
            if (q == null || p.margin() == 0) {
                continue;
            }
            long value = q.lastPaise() * Math.abs(p.quantity());
            long pnl = p.quantity() > 0 ? value - p.cost() : p.cost() - value;
            if (-pnl * 100 >= p.margin() * props.riskLossPercent()) {
                orders.squareOff(p.user(), p.symbol(), p.session(), "Closed early: the loss reached " + props.riskLossPercent() + "% of the margin.");
            }
        }
    }

    /** At square-off time, intraday orders that haven't executed are cancelled: they could only open new positions. */
    private void cancelWorkingIntraday(Market m) {
        List<UUID> working = db.sql("""
                        SELECT id FROM orders WHERE product = 'MIS' AND status IN ('PENDING', 'OPEN') AND NOT auto_square_off
                          AND opening AND (position_session IS NULL OR position_session <= ?)""")
                .param(m.sessionDate()).query(UUID.class).list();
        for (UUID id : working) {
            try {
                Reply r = up.cancelOnExchange(id);
                if (r.ok()) {
                    orders.applyExchange(id, r.body());
                }
            } catch (Unreachable e) {
                return;
            }
        }
    }

    /** Takes what customers owe from their cash, as far as it goes. */
    void recoverDues() {
        List<UUID> owing = db.sql("SELECT user_id FROM dues ORDER BY since LIMIT 100").query(UUID.class).list();
        for (UUID user : owing) {
            try {
                long owed = up.balance(dues(user));
                if (owed == 0) {
                    db.sql("DELETE FROM dues WHERE user_id = ?").param(user).update();
                    continue;
                }
                long take = Math.min(owed, up.balance(cash(user)));
                if (take > 0) {
                    Reply r = up.post("dues-recovery:" + UUID.randomUUID(), "Recovered what was owed from an intraday loss", user.toString(),
                            new Entry().debit(cash(user), take).credit(dues(user), take).legs());
                    if (r.ok()) {
                        log.info("Recovered ₹{} of ₹{} owed by {}", Money.rupees(take), Money.rupees(owed), user);
                    }
                }
            } catch (Unreachable e) {
                return;
            }
        }
    }
}
