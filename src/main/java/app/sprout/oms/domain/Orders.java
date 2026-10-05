package app.sprout.oms.domain;

import static app.sprout.oms.domain.LedgerOutbox.cash;
import static app.sprout.oms.domain.LedgerOutbox.dues;
import static app.sprout.oms.domain.LedgerOutbox.hold;
import static app.sprout.oms.domain.LedgerOutbox.unsettled;

import app.sprout.oms.config.OmsProperties;
import app.sprout.oms.domain.Charges.Breakdown;
import app.sprout.oms.domain.Charges.Product;
import app.sprout.oms.domain.Charges.Side;
import app.sprout.oms.domain.LedgerOutbox.Entry;
import app.sprout.oms.domain.Upstreams.Instrument;
import app.sprout.oms.domain.Upstreams.Market;
import app.sprout.oms.domain.Upstreams.Quote;
import app.sprout.oms.domain.Upstreams.Reply;
import app.sprout.oms.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An order's life: risk checks and the money blocked for it, the trip to the exchange, and what its
 * execution does to the customer's holdings, positions and money.
 *
 * <p>Rules that keep the books right:
 * <ul>
 *   <li>Checks that depend on what else the customer has working (shares already being sold, an
 *       intraday position already being closed) run under a per-customer lock, so two orders can't
 *       both use the same shares.</li>
 *   <li>Money is blocked in the ledger before the order goes anywhere; the order records exactly
 *       what is blocked for it.</li>
 *   <li>The exchange is the truth about executions. Its answer, its callback or (when both are
 *       lost) the reconciler's question apply the same change, at most once, under the order's row
 *       lock; the ledger entries that change implies are written in the same transaction (see
 *       {@link LedgerOutbox}).</li>
 * </ul>
 */
@Service
public class Orders {

    private static final Logger log = LoggerFactory.getLogger(Orders.class);

    public enum OrderType { MARKET, LIMIT }

    public enum Variety { REGULAR, AMO }

    public record NewOrder(String symbol, Side side, Integer quantity, OrderType orderType, Long limitPaise, Product product,
                           Variety variety) {}

    public record Order(UUID id, UUID userId, String symbol, Side side, int quantity, OrderType orderType, Long limitPaise,
                        Long protectionPaise, Product product, Variety variety, String status, boolean opening, long holdPaise,
                        long blockedPaise, LocalDate positionSession, Long fillPricePaise, Breakdown charges, Long realisedPnlPaise,
                        boolean autoSquareOff, String rejectionCode, String rejectionMessage, String reason, Instant filledAt,
                        Instant createdAt, Instant updatedAt) {

        public boolean working() {
            return status.equals("AMO_QUEUED") || status.equals("PENDING") || status.equals("OPEN");
        }
    }

    public record Placed(Order order, boolean created) {}

    private record Rejection(String code, String message) {}

    private record Decision(Rejection rejection, boolean opening, long hold, Long protection) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Upstreams up;
    private final LedgerOutbox ledger;
    private final OmsProperties props;

    public Orders(JdbcClient db, TransactionTemplate tx, Clock clock, Upstreams up, LedgerOutbox ledger, OmsProperties props) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.up = up;
        this.ledger = ledger;
        this.props = props;
    }

    // ── placing ──────────────────────────────────────────────────────────────

    public Placed place(UUID user, String key, NewOrder o) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
        validate(o);
        Variety variety = o.variety() == null ? Variety.REGULAR : o.variety();
        String hash = hash(o, variety);
        Optional<Placed> earlier = byKey(user, key, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Instrument instrument;
        Quote quote;
        try {
            up.requireAccount(user);
            instrument = up.instrument(o.symbol()).filter(Instrument::tradable)
                    .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_INSTRUMENT, "There's no tradable share called " + o.symbol() + "."));
            quote = up.quotes(List.of(o.symbol())).get(o.symbol());
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
        if (quote == null) {
            throw Upstreams.unavailable();
        }
        UUID id = UUID.randomUUID();
        Order placed;
        try {
            placed = tx.execute(s -> {
                lockCustomer(user);
                Decision d = decide(user, o, variety, instrument, quote);
                Instant now = clock.instant();
                db.sql("""
                                INSERT INTO orders (id, user_id, idempotency_key, request_hash, symbol, side, quantity, order_type, limit_paise,
                                                    protection_paise, product, variety, status, opening, hold_paise, position_session,
                                                    rejection_code, rejection_message, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(id, user, key, hash, o.symbol(), o.side().name(), o.quantity(), o.orderType().name(), o.limitPaise(),
                                d.protection(), o.product().name(), variety.name(), d.rejection() == null ? "PENDING" : "REJECTED",
                                d.opening(), d.hold(), variety == Variety.REGULAR && o.product() == Product.MIS ? quote.market().sessionDate() : null,
                                d.rejection() == null ? null : d.rejection().code(), d.rejection() == null ? null : d.rejection().message(),
                                ts(now), ts(now))
                        .update();
                return order(id);
            });
        } catch (DuplicateKeyException e) {
            return byKey(user, key, hash).orElseThrow(() -> e);
        }
        if (placed.status().equals("REJECTED")) {
            return new Placed(placed, true);
        }
        if (placed.holdPaise() > 0 && !blockMoney(placed)) {
            return new Placed(order(id), true);
        }
        if (variety == Variety.AMO) {
            db.sql("UPDATE orders SET status = 'AMO_QUEUED', updated_at = ? WHERE id = ? AND status = 'PENDING'").params(ts(clock.instant()), id).update();
        } else {
            send(order(id));
        }
        ledger.flush();
        return new Placed(order(id), true);
    }

    private void validate(NewOrder o) {
        if (o.symbol() == null || o.side() == null || o.orderType() == null || o.product() == null || o.quantity() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "symbol, side, quantity, orderType and product are required.");
        }
        if (o.quantity() < 1 || o.quantity() > 100_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity is between 1 and 1,00,000 shares.");
        }
        if (o.orderType() == OrderType.LIMIT && o.limitPaise() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A LIMIT order needs a limitPrice.");
        }
        if (o.orderType() == OrderType.MARKET && o.limitPaise() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A MARKET order takes no limitPrice.");
        }
        if (o.limitPaise() != null && o.limitPaise() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limitPrice must be above zero.");
        }
    }

    /** The risk checks. Runs under the customer's lock, so what it counts as already working is stable. */
    private Decision decide(UUID user, NewOrder o, Variety variety, Instrument instrument, Quote q) {
        Market m = q.market();
        if (variety == Variety.REGULAR && !m.open()) {
            return reject("MARKET_CLOSED", "The market is closed (it trades 09:15 to 15:30). Place an AMO to send it at the next open.");
        }
        if (variety == Variety.AMO && m.open()) {
            return reject("MARKET_OPEN", "The market is open: place a regular order. AMOs are for when it's closed.");
        }
        Long protection = null;
        long basis;
        if (o.orderType() == OrderType.LIMIT) {
            long tick = instrument.tickPaise();
            if (o.limitPaise() % tick != 0) {
                return reject("INVALID_TICK", o.symbol() + " moves in steps of ₹" + Money.rupees(tick) + ".");
            }
            long width = q.prevClosePaise() * props.bandPercent() / 100;
            if (o.limitPaise() < q.prevClosePaise() - width || o.limitPaise() > q.prevClosePaise() + width) {
                return reject("PRICE_OUT_OF_BAND", "Today " + o.symbol() + " can trade between ₹" + Money.rupees(q.prevClosePaise() - width)
                        + " and ₹" + Money.rupees(q.prevClosePaise() + width) + ".");
            }
            basis = o.limitPaise();
        } else {
            protection = protection(o.side(), q.lastPaise(), instrument.tickPaise());
            basis = protection;
        }
        long value = Math.multiplyExact(basis, (long) o.quantity());

        if (o.product() == Product.CNC) {
            if (o.side() == Side.SELL) {
                int held = holding(user, o.symbol()).map(h -> h[0]).orElse(0L).intValue();
                int selling = workingQuantity(user, o.symbol(), Product.CNC, Side.SELL, null);
                if (o.quantity() > held - selling) {
                    return reject("INSUFFICIENT_HOLDINGS", "You can sell up to " + Math.max(0, held - selling) + " " + o.symbol()
                            + " (you hold " + held + (selling > 0 ? ", " + selling + " already being sold" : "") + ").");
                }
                return new Decision(null, false, 0, protection);
            }
            return new Decision(null, true, value + Charges.of(Product.CNC, Side.BUY, value, false).total(), protection);
        }

        // intraday: does this close (part of) a position, or open one?
        LocalDate session = m.sessionDate();
        long position = position(user, o.symbol(), session).map(p -> p[0]).orElse(0L);
        boolean closingSide = (position > 0 && o.side() == Side.SELL) || (position < 0 && o.side() == Side.BUY);
        if (closingSide) {
            int closing = workingQuantity(user, o.symbol(), Product.MIS, o.side(), session);
            long closable = Math.abs(position) - closing;
            if (o.quantity() <= closable) {
                return new Decision(null, false, 0, protection);
            }
            return reject("POSITION_FLIP", "You can close up to " + Math.max(0, closable) + " " + o.symbol()
                    + ". To trade the other way, close the position first.");
        }
        if (m.open() && !m.time().isBefore(props.squareOffAt())) {
            return reject("INTRADAY_CLOSED", "New intraday positions close at " + props.squareOffAt() + ". Use delivery (CNC) instead.");
        }
        long margin = ceilDiv(value, props.leverage());
        return new Decision(null, true, margin + Charges.of(Product.MIS, o.side(), value, false).total(), protection);
    }

    /** The worst price a market order may execute at: the price now, plus or minus the protection, on the tick. */
    long protection(Side side, long last, long tick) {
        if (side == Side.BUY) {
            return Math.max(tick, (last * (100 + props.marketProtectionPercent()) / 100) / tick * tick);
        }
        long low = last * (100 - props.marketProtectionPercent()) / 100;
        return Math.max(tick, ceilDiv(low, tick) * tick);
    }

    private static Decision reject(String code, String message) {
        return new Decision(new Rejection(code, message), false, 0, null);
    }

    /** Blocks the order's money in the ledger. Returns false (and ends the order) if it can't. */
    private boolean blockMoney(Order o) {
        Reply r;
        try {
            r = up.post("order-hold:" + o.id(), "Money blocked for an order", o.id().toString(), LedgerOutbox.holdLegs(o.userId(), o.holdPaise()));
        } catch (Unreachable e) {
            // the hold may or may not have been booked: make sure it ends at zero, and refuse the order
            tx.executeWithoutResult(s -> {
                db.sql("UPDATE orders SET status = 'REJECTED', rejection_code = 'UNAVAILABLE', rejection_message = ?, updated_at = ? WHERE id = ?")
                        .params("Your money couldn't be blocked just now, so nothing was placed. Try again shortly.", ts(clock.instant()), o.id())
                        .update();
                ledger.undoHold(o.id(), o.userId(), o.holdPaise());
            });
            ledger.flush();
            return false;
        }
        if (!r.ok()) {
            boolean poor = r.code().equals("INSUFFICIENT_FUNDS");
            String message;
            if (poor) {
                long available = 0;
                try {
                    available = up.balance(cash(o.userId()));
                } catch (Unreachable ignored) {
                    // the message just won't say what is available
                }
                message = "This order needs ₹" + Money.rupees(o.holdPaise()) + " and you have ₹" + Money.rupees(available) + " available.";
            } else {
                message = "Your money couldn't be blocked (" + r.code() + "), so nothing was placed.";
            }
            db.sql("UPDATE orders SET status = 'REJECTED', rejection_code = ?, rejection_message = ?, updated_at = ? WHERE id = ?")
                    .params(poor ? "INSUFFICIENT_FUNDS" : "UNAVAILABLE", message, ts(clock.instant()), o.id()).update();
            return false;
        }
        db.sql("UPDATE orders SET blocked_paise = hold_paise, updated_at = ? WHERE id = ?").params(ts(clock.instant()), o.id()).update();
        return true;
    }

    // ── the exchange ─────────────────────────────────────────────────────────

    /** Sends a PENDING order to the exchange and applies whatever it answers. Unknown outcomes stay PENDING. */
    public void send(Order o) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientOrderId", o.id().toString());
        body.put("symbol", o.symbol());
        body.put("side", o.side().name());
        body.put("type", o.orderType().name());
        body.put("quantity", o.quantity());
        if (o.limitPaise() != null) {
            body.put("limitPrice", Money.rupees(o.limitPaise()));
        }
        if (o.protectionPaise() != null) {
            body.put("protectionPrice", Money.rupees(o.protectionPaise()));
        }
        Reply r;
        try {
            r = up.placeOnExchange(body);
        } catch (Unreachable e) {
            log.info("Order {} not confirmed by the exchange yet ({}); the reconciler will ask", o.id(), e.getMessage());
            return;
        }
        if (r.ok()) {
            applyExchange(o.id(), r.body());
            return;
        }
        String code = switch (r.code()) {
            case "MARKET_CLOSED", "PRICE_OUT_OF_BAND", "INVALID_TICK" -> r.code();
            default -> "UNAVAILABLE";
        };
        String detail = r.body() == null ? "" : r.body().path("detail").asText("");
        end(o.id(), "REJECTED", null, code, detail.isEmpty() ? "The exchange refused the order (" + r.code() + ")." : "The exchange refused it: " + detail);
    }

    /** Applies the exchange's view of an order (its answer, or what it says when asked). */
    public void applyExchange(UUID id, JsonNode ex) {
        switch (ex.path("status").asText()) {
            case "FILLED" -> fill(id, Money.paise(ex.path("price").asText()), UUID.fromString(ex.path("tradeId").asText()));
            case "OPEN" -> db.sql("UPDATE orders SET status = 'OPEN', updated_at = ? WHERE id = ? AND status = 'PENDING'")
                    .params(ts(clock.instant()), id).update();
            case "CANCELLED" -> end(id, "CANCELLED", ex.path("reason").asText("Cancelled."), null, null);
            case "EXPIRED" -> end(id, "EXPIRED", ex.path("reason").asText("The market closed before it executed."), null, null);
            default -> log.warn("Exchange says order {} is {}; ignoring", id, ex.path("status").asText());
        }
    }

    /** An execution report from the exchange's callback. */
    public void onExecution(String type, UUID id, long pricePaise, UUID tradeId, String reason) {
        switch (type) {
            case "ORDER_FILLED" -> fill(id, pricePaise, tradeId);
            case "ORDER_CANCELLED" -> end(id, "CANCELLED", reason == null ? "Cancelled." : reason, null, null);
            case "ORDER_EXPIRED" -> end(id, "EXPIRED", reason == null ? "The market closed before it executed." : reason, null, null);
            default -> log.warn("Unknown execution report {} for order {}", type, id);
        }
    }

    /**
     * Ends a working order without an execution, giving back what was blocked. Does nothing if the
     * order has already ended.
     */
    public void end(UUID id, String status, String reason, String rejectionCode, String rejectionMessage) {
        tx.executeWithoutResult(s -> {
            Order o = lock(id).orElse(null);
            if (o == null || !o.working()) {
                return;
            }
            db.sql("UPDATE orders SET status = ?, reason = ?, rejection_code = ?, rejection_message = ?, blocked_paise = 0, updated_at = ? WHERE id = ?")
                    .params(status, reason, rejectionCode, rejectionMessage, ts(clock.instant()), id).update();
            if (o.blockedPaise() > 0) {
                ledger.add("release:" + id, "Order " + status.toLowerCase() + ": money released", id.toString(),
                        new Entry().debit(hold(o.userId()), o.blockedPaise()).credit(cash(o.userId()), o.blockedPaise()));
            }
        });
        ledger.flush();
    }

    // ── executions ───────────────────────────────────────────────────────────

    /**
     * Books an execution: the order, the customer's holdings or intraday position, and the ledger
     * entry for the money, all in one transaction. Applying the same execution again does nothing.
     */
    public void fill(UUID id, long price, UUID tradeId) {
        tx.executeWithoutResult(s -> {
            Order o = lock(id).orElse(null);
            if (o == null || o.status().equals("FILLED")) {
                return;
            }
            if (!o.working()) {
                log.error("The exchange filled order {}, which Sprout had as {}; booking it anyway", id, o.status());
            }
            lockCustomer(o.userId());
            UUID user = o.userId();
            long value = Math.multiplyExact(price, (long) o.quantity());
            Breakdown charges = Charges.of(o.product(), o.side(), value, o.autoSquareOff());
            Entry e = new Entry().debit(hold(user), o.blockedPaise());
            long net = o.blockedPaise() - charges.total();
            long proceeds = 0;
            Long realised = null;

            if (o.product() == Product.CNC) {
                long[] h = holding(user, o.symbol()).orElse(new long[] {0, 0, 0});
                if (o.side() == Side.BUY) {
                    e.credit("sprout:clearing-payable", value);
                    net -= value;
                    upsertHolding(user, o.symbol(), h[0] + o.quantity(), h[1] + o.quantity(), h[2] + value);
                } else {
                    long sold = Math.min(o.quantity(), h[0]);
                    if (sold < o.quantity()) {
                        log.error("Order {} sold {} {} but only {} were held", id, o.quantity(), o.symbol(), h[0]);
                    }
                    long cost = h[0] == 0 ? 0 : (sold == h[0] ? h[2] : h[2] * sold / h[0]);
                    long left = h[0] - sold;
                    upsertHolding(user, o.symbol(), left, Math.min(h[1], left), h[2] - cost);
                    e.debit("sprout:clearing-receivable", value);
                    proceeds += value;
                    realised = value - cost;
                }
            } else {
                LocalDate session = o.positionSession() != null ? o.positionSession() : up.market().sessionDate();
                long[] p = position(user, o.symbol(), session).orElse(new long[] {0, 0, 0, 0});
                long qty = p[0];
                long cost = p[1];
                long margin = p[2];
                long pnl = p[3];
                boolean reduces = (qty > 0 && o.side() == Side.SELL) || (qty < 0 && o.side() == Side.BUY);
                long closing = reduces ? Math.min(o.quantity(), Math.abs(qty)) : 0;
                long opening = o.quantity() - closing;
                if (closing > 0) {
                    boolean all = closing == Math.abs(qty);
                    long c = all ? cost : cost * closing / Math.abs(qty);
                    long m = all ? margin : margin * closing / Math.abs(qty);
                    long v = price * closing;
                    long r = qty > 0 ? v - c : c - v;
                    e.debit(hold(user), m);
                    net += m;
                    if (r > 0) {
                        e.debit("sprout:clearing-receivable", r);
                        proceeds += r;
                    } else if (r < 0) {
                        e.credit("sprout:clearing-payable", -r);
                        net += r;
                    }
                    qty += qty > 0 ? -closing : closing;
                    cost -= c;
                    margin -= m;
                    pnl += r;
                    realised = r;
                }
                if (opening > 0) {
                    long v = price * opening;
                    long m = ceilDiv(v, props.leverage());
                    e.credit(hold(user), m);
                    net -= m;
                    qty += o.side() == Side.BUY ? opening : -opening;
                    cost += v;
                    margin += m;
                }
                upsertPosition(user, o.symbol(), session, qty, cost, margin, pnl);
            }

            // what the customer is left with: back to cash, or a shortfall taken from this trade's proceeds, then owed
            if (net > 0) {
                e.credit(cash(user), net);
            } else if (net < 0) {
                long shortfall = -net;
                long fromProceeds = Math.min(shortfall, proceeds);
                proceeds -= fromProceeds;
                shortfall -= fromProceeds;
                if (shortfall > 0) {
                    e.debit(dues(user), shortfall);
                    db.sql("INSERT INTO dues (user_id, since) VALUES (?, ?) ON CONFLICT DO NOTHING").params(user, ts(clock.instant())).update();
                    log.warn("Order {} left customer {} owing ₹{}", id, user, Money.rupees(shortfall));
                }
            }
            e.credit(unsettled(user), proceeds);
            e.credit("sprout:income:brokerage", charges.brokerage()).credit("sprout:payable:stt", charges.stt())
                    .credit("sprout:payable:exchange-charges", charges.exchange()).credit("sprout:payable:sebi-fees", charges.sebi())
                    .credit("sprout:payable:stamp-duty", charges.stamp()).credit("sprout:payable:gst", charges.gst());

            Instant now = clock.instant();
            db.sql("""
                            UPDATE orders SET status = 'FILLED', blocked_paise = 0, fill_price_paise = ?, trade_id = ?, brokerage_paise = ?,
                                   stt_paise = ?, exchange_paise = ?, sebi_paise = ?, stamp_paise = ?, gst_paise = ?, realised_pnl_paise = ?,
                                   filled_at = ?, updated_at = ? WHERE id = ?""")
                    .params(price, tradeId, charges.brokerage(), charges.stt(), charges.exchange(), charges.sebi(), charges.stamp(),
                            charges.gst(), realised, ts(now), ts(now), id)
                    .update();
            ledger.add("fill:" + id, (o.side() == Side.BUY ? "Bought " : "Sold ") + o.quantity() + " " + o.symbol() + " at ₹"
                    + Money.rupees(price) + " (" + o.product() + ")", id.toString(), e);
            log.info("Booked {} {} {} x{} at {} for {}", o.product(), o.side(), o.symbol(), o.quantity(), Money.rupees(price), user);
        });
        ledger.flush();
    }

    // ── cancelling ───────────────────────────────────────────────────────────

    public Order cancel(UUID user, UUID id) {
        Order o = mine(user, id);
        switch (o.status()) {
            case "AMO_QUEUED" -> end(id, "CANCELLED", "Cancelled by you.", null, null);
            case "OPEN", "PENDING" -> {
                Reply r;
                try {
                    r = up.cancelOnExchange(id);
                } catch (Unreachable e) {
                    throw Upstreams.unavailable();
                }
                if (r.ok()) {
                    applyExchange(id, r.body());
                } else if (r.code().equals("ORDER_NOT_OPEN")) {
                    try {
                        Reply now = up.exchangeOrder(id);
                        if (now.ok()) {
                            applyExchange(id, now.body());
                        }
                    } catch (Unreachable ignored) {
                        // the callback will bring the news
                    }
                    throw new ApiException(ErrorCode.ORDER_NOT_OPEN, "Too late: the order already executed or ended.");
                } else {
                    // the exchange doesn't have it (yet): it may still be on its way
                    throw Upstreams.unavailable();
                }
            }
            default -> throw new ApiException(ErrorCode.ORDER_NOT_OPEN, "The order is already " + o.status() + ".");
        }
        return order(id);
    }

    // ── after-market orders, square-off and reconciliation (driven by Rms) ───

    /** Sends queued after-market orders once the market is open. */
    public int dispatchAmos(Market m) {
        List<Order> queued = db.sql(ORDER_SQL + " WHERE status = 'AMO_QUEUED' ORDER BY created_at LIMIT 200").query(Orders::row).list();
        for (Order o : queued) {
            if (o.product() == Product.MIS && o.opening() && !m.time().isBefore(props.squareOffAt())) {
                end(o.id(), "REJECTED", null, "INTRADAY_CLOSED", "The market opened too late in the day for a new intraday position.");
                continue;
            }
            int moved = db.sql("UPDATE orders SET status = 'PENDING', position_session = CASE WHEN product = 'MIS' THEN ? END, updated_at = ? "
                            + "WHERE id = ? AND status = 'AMO_QUEUED'")
                    .params(m.sessionDate(), ts(clock.instant()), o.id()).update();
            if (moved == 1) {
                send(order(o.id()));
            }
        }
        return queued.size();
    }

    /**
     * Closes an intraday position with a market order placed by Sprout: cancels the customer's other
     * working intraday orders in that share first, so nothing can execute after it and open a new
     * position. Does nothing while a square-off for the position is already working.
     */
    public void squareOff(UUID user, String symbol, LocalDate session, String why) {
        List<Order> working = db.sql(ORDER_SQL + " WHERE user_id = ? AND symbol = ? AND product = 'MIS' AND status IN ('PENDING', 'OPEN', 'AMO_QUEUED')")
                .params(user, symbol).query(Orders::row).list();
        if (working.stream().anyMatch(Order::autoSquareOff)) {
            return;
        }
        for (Order w : working) {
            if (w.status().equals("AMO_QUEUED")) {
                end(w.id(), "CANCELLED", "Cancelled: Sprout closed the position.", null, null);
                continue;
            }
            try {
                Reply r = up.cancelOnExchange(w.id());
                if (r.ok()) {
                    applyExchange(w.id(), r.body());
                } else {
                    Reply now = up.exchangeOrder(w.id());
                    if (now.ok()) {
                        applyExchange(w.id(), now.body());
                    }
                }
            } catch (Unreachable e) {
                return;   // try the whole square-off again next round
            }
            if (order(w.id()).working()) {
                return;
            }
        }
        UUID id = UUID.randomUUID();
        Boolean placed = tx.execute(s -> {
            lockCustomer(user);
            long qty = position(user, symbol, session).map(p -> p[0]).orElse(0L);
            if (qty == 0) {
                return false;
            }
            Side side = qty > 0 ? Side.SELL : Side.BUY;
            Instant now = clock.instant();
            db.sql("""
                            INSERT INTO orders (id, user_id, idempotency_key, request_hash, symbol, side, quantity, order_type, product, variety,
                                                status, opening, position_session, auto_square_off, reason, created_at, updated_at)
                            VALUES (?, ?, ?, '', ?, ?, ?, 'MARKET', 'MIS', 'REGULAR', 'PENDING', false, ?, true, ?, ?, ?)""")
                    .params(id, user, "square-off:" + id, symbol, side.name(), (int) Math.abs(qty), session, why, ts(now), ts(now)).update();
            return true;
        });
        if (Boolean.TRUE.equals(placed)) {
            log.info("Squaring off {} {} for {} ({})", symbol, session, user, why);
            send(order(id));
        }
    }

    /** Asks the exchange about orders whose outcome Sprout hasn't heard. */
    public int reconcile(Instant olderThan) {
        List<Order> unsure = db.sql(ORDER_SQL + " WHERE status IN ('PENDING', 'OPEN') AND updated_at < ? ORDER BY updated_at LIMIT 100")
                .param(ts(olderThan)).query(Orders::row).list();
        int settled = 0;
        Instant giveUp = olderThan.minusSeconds(20);
        for (Order o : unsure) {
            if (o.status().equals("PENDING") && o.holdPaise() > 0 && o.blockedPaise() == 0) {
                // placing it stopped between blocking the money and recording that: make sure the hold ends at zero
                if (o.updatedAt().isBefore(giveUp)) {
                    tx.executeWithoutResult(s -> {
                        if (db.sql("UPDATE orders SET status = 'REJECTED', rejection_code = 'UNAVAILABLE', rejection_message = ?, updated_at = ? "
                                        + "WHERE id = ? AND status = 'PENDING' AND blocked_paise = 0")
                                .params("Placing the order didn't finish, so nothing was placed.", ts(clock.instant()), o.id()).update() == 1) {
                            ledger.undoHold(o.id(), o.userId(), o.holdPaise());
                        }
                    });
                    ledger.flush();
                    settled++;
                }
                continue;
            }
            try {
                Reply r = up.exchangeOrder(o.id());
                if (r.ok()) {
                    applyExchange(o.id(), r.body());
                    settled += order(o.id()).working() ? 0 : 1;
                } else if (r.status() == 404 && o.status().equals("PENDING") && o.updatedAt().isBefore(giveUp)) {
                    end(o.id(), "REJECTED", null, "UNAVAILABLE", "The order never reached the exchange. Nothing was placed.");
                    settled++;
                }
            } catch (Unreachable e) {
                return settled;
            }
            db.sql("UPDATE orders SET updated_at = ? WHERE id = ? AND status IN ('PENDING', 'OPEN')").params(ts(clock.instant()), o.id()).update();
        }
        return settled;
    }

    // ── reading ──────────────────────────────────────────────────────────────

    public Order mine(UUID user, UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ? AND user_id = ?").params(id, user).query(Orders::row).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such order of yours."));
    }

    public List<Order> mine(UUID user) {
        return db.sql(ORDER_SQL + " WHERE user_id = ? ORDER BY created_at DESC LIMIT 100").param(user).query(Orders::row).list();
    }

    public Order order(UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ?").param(id).query(Orders::row).single();
    }

    private Optional<Order> lock(UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ? FOR UPDATE").param(id).query(Orders::row).optional();
    }

    private Optional<Placed> byKey(UUID user, String key, String hash) {
        return db.sql("SELECT id, request_hash FROM orders WHERE user_id = ? AND idempotency_key = ?").params(user, key)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("request_hash")})
                .optional()
                .map(row -> {
                    if (!row[1].equals(hash)) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "This Idempotency-Key was already used for a different order.");
                    }
                    return new Placed(order((UUID) row[0]), false);
                });
    }

    /** Serialises everything that reads and changes one customer's shares and positions. */
    private void lockCustomer(UUID user) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param(user.toString()).query((rs, n) -> 1).list();
    }

    private int workingQuantity(UUID user, String symbol, Product product, Side side, LocalDate session) {
        return db.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM orders
                        WHERE user_id = ? AND symbol = ? AND product = ? AND side = ? AND status IN ('AMO_QUEUED', 'PENDING', 'OPEN')
                          AND (CAST(? AS date) IS NULL OR position_session = ? OR position_session IS NULL)""")
                .params(user, symbol, product.name(), side.name(), session, session).query(Integer.class).single();
    }

    /** [quantity, t1 quantity, cost] */
    Optional<long[]> holding(UUID user, String symbol) {
        return db.sql("SELECT quantity, t1_quantity, cost_paise FROM holdings WHERE user_id = ? AND symbol = ?").params(user, symbol)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).optional();
    }

    private void upsertHolding(UUID user, String symbol, long qty, long t1, long cost) {
        db.sql("""
                        INSERT INTO holdings (user_id, symbol, quantity, t1_quantity, cost_paise) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, symbol) DO UPDATE SET quantity = EXCLUDED.quantity, t1_quantity = EXCLUDED.t1_quantity,
                                                                    cost_paise = EXCLUDED.cost_paise""")
                .params(user, symbol, qty, t1, cost).update();
    }

    /** [quantity, cost, margin, realised P&amp;L] */
    Optional<long[]> position(UUID user, String symbol, LocalDate session) {
        return db.sql("SELECT quantity, cost_paise, margin_paise, realised_pnl_paise FROM positions WHERE user_id = ? AND symbol = ? AND session_date = ?")
                .params(user, symbol, session)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).optional();
    }

    private void upsertPosition(UUID user, String symbol, LocalDate session, long qty, long cost, long margin, long pnl) {
        db.sql("""
                        INSERT INTO positions (user_id, symbol, session_date, quantity, cost_paise, margin_paise, realised_pnl_paise)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, symbol, session_date) DO UPDATE SET quantity = EXCLUDED.quantity, cost_paise = EXCLUDED.cost_paise,
                            margin_paise = EXCLUDED.margin_paise, realised_pnl_paise = EXCLUDED.realised_pnl_paise""")
                .params(user, symbol, session, (int) qty, cost, margin, pnl).update();
    }

    private static final String ORDER_SQL = """
            SELECT id, user_id, symbol, side, quantity, order_type, limit_paise, protection_paise, product, variety, status, opening,
                   hold_paise, blocked_paise, position_session, fill_price_paise, brokerage_paise, stt_paise, exchange_paise, sebi_paise,
                   stamp_paise, gst_paise, realised_pnl_paise, auto_square_off, rejection_code, rejection_message, reason, filled_at,
                   created_at, updated_at FROM orders""";

    private static Order row(ResultSet rs, int n) throws SQLException {
        Breakdown charges = rs.getObject("brokerage_paise") == null ? null
                : new Breakdown(rs.getLong("brokerage_paise"), rs.getLong("stt_paise"), rs.getLong("exchange_paise"),
                        rs.getLong("sebi_paise"), rs.getLong("stamp_paise"), rs.getLong("gst_paise"));
        Timestamp filled = rs.getTimestamp("filled_at");
        return new Order(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("symbol"),
                Side.valueOf(rs.getString("side")), rs.getInt("quantity"), OrderType.valueOf(rs.getString("order_type")),
                rs.getObject("limit_paise", Long.class), rs.getObject("protection_paise", Long.class),
                Product.valueOf(rs.getString("product")), Variety.valueOf(rs.getString("variety")), rs.getString("status"),
                rs.getBoolean("opening"), rs.getLong("hold_paise"), rs.getLong("blocked_paise"),
                rs.getObject("position_session", LocalDate.class), rs.getObject("fill_price_paise", Long.class), charges,
                rs.getObject("realised_pnl_paise", Long.class), rs.getBoolean("auto_square_off"), rs.getString("rejection_code"),
                rs.getString("rejection_message"), rs.getString("reason"), filled == null ? null : filled.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    static String hash(NewOrder o, Variety variety) {
        String canonical = String.join("|", o.symbol(), o.side().name(), String.valueOf(o.quantity()), o.orderType().name(),
                String.valueOf(o.limitPaise()), o.product().name(), variety.name());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long ceilDiv(long a, long b) {
        return Math.floorDiv(a + b - 1, b);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
