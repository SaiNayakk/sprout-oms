package app.sprout.oms.web;

import app.sprout.oms.config.OmsProperties;
import app.sprout.oms.domain.ApiException;
import app.sprout.oms.domain.Charges.Breakdown;
import app.sprout.oms.domain.Charges.Product;
import app.sprout.oms.domain.Charges.Side;
import app.sprout.oms.domain.ErrorCode;
import app.sprout.oms.domain.Money;
import app.sprout.oms.domain.Orders;
import app.sprout.oms.domain.Orders.NewOrder;
import app.sprout.oms.domain.Orders.Order;
import app.sprout.oms.domain.Orders.OrderType;
import app.sprout.oms.domain.Orders.Placed;
import app.sprout.oms.domain.Orders.Variety;
import app.sprout.oms.domain.Portfolio;
import app.sprout.oms.domain.Settlements;
import app.sprout.oms.domain.Settlements.Delivery;
import app.sprout.oms.domain.Settlements.Shortage;
import app.sprout.oms.domain.Settlements.Summary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The orders API (oms-v1.yaml), plus the signed callback the exchange calls. */
@RestController
public class OmsController {

    private static final Logger log = LoggerFactory.getLogger(OmsController.class);

    public record NewOrderRequest(String symbol, Side side, Integer quantity, OrderType orderType, String limitPrice, Product product,
                                  Variety variety) {}

    public record ShortageBody(String clientCode, String symbol, Long quantity, String closeOutValue) {}

    public record ShortagesRequest(String settlementId, List<ShortageBody> shortages) {}

    public record DeliveryBody(String clientCode, String symbol, Long quantity) {}

    public record CompleteRequest(String settlementId, List<DeliveryBody> deliveries) {}

    private final Orders orders;
    private final Settlements settlements;
    private final Portfolio portfolio;
    private final OmsProperties props;
    private final ObjectMapper json;
    private final JdbcClient db;
    private final Clock clock;

    public OmsController(Orders orders, Settlements settlements, Portfolio portfolio, OmsProperties props, ObjectMapper json, JdbcClient db,
                         Clock clock) {
        this.orders = orders;
        this.settlements = settlements;
        this.portfolio = portfolio;
        this.props = props;
        this.json = json;
        this.db = db;
        this.clock = clock;
    }

    @PostMapping("/v1/orders")
    public ResponseEntity<Map<String, Object>> place(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @RequestBody NewOrderRequest req) {
        Placed p = orders.place(userId(user), key, new NewOrder(req.symbol() == null ? null : req.symbol().trim().toUpperCase(),
                req.side(), req.quantity(), req.orderType(), req.limitPrice() == null ? null : Money.paise(req.limitPrice()),
                req.product(), req.variety()));
        return ResponseEntity.status(p.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(p.order()));
    }

    @GetMapping("/v1/orders")
    public Map<String, Object> list(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("orders", orders.mine(userId(user)).stream().map(OmsController::dto).toList());
    }

    @GetMapping("/v1/orders/{id}")
    public Map<String, Object> get(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(orders.mine(userId(user), id));
    }

    @DeleteMapping("/v1/orders/{id}")
    public Map<String, Object> cancel(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(orders.cancel(userId(user), id));
    }

    @GetMapping("/v1/holdings")
    public Map<String, Object> holdings(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("holdings", portfolio.holdings(userId(user)));
    }

    @GetMapping("/v1/positions")
    public Map<String, Object> positions(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("positions", portfolio.positions(userId(user)));
    }

    @GetMapping("/v1/funds")
    public Map<String, Object> funds(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return portfolio.funds(userId(user));
    }

    /** The exchange's execution reports. Verified, applied at most once per eventId. */
    @PostMapping("/internal/v1/exchange-events")
    public ResponseEntity<Void> exchangeEvent(@RequestHeader(value = "X-Exchange-Signature", required = false) String signature,
                                              @RequestBody byte[] raw) throws Exception {
        String body = new String(raw, StandardCharsets.UTF_8);
        String expected = "sha256=" + hmac(props.exchange().webhookSecret(), body);
        if (signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "This isn't signed by the exchange.");
        }
        JsonNode e = json.readTree(body);
        UUID eventId = UUID.fromString(e.path("eventId").asText());
        if (db.sql("SELECT 1 FROM exchange_events WHERE event_id = ?").param(eventId).query(Integer.class).optional().isPresent()) {
            return ResponseEntity.noContent().build();
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(e.path("clientOrderId").asText());
        } catch (IllegalArgumentException bad) {
            log.warn("Exchange event {} for an order id that isn't ours ({}); ignoring", eventId, e.path("clientOrderId").asText());
            remember(eventId);
            return ResponseEntity.noContent().build();
        }
        orders.onExecution(e.path("type").asText(), orderId, e.hasNonNull("price") ? Money.paise(e.path("price").asText()) : 0,
                e.hasNonNull("tradeId") ? UUID.fromString(e.path("tradeId").asText()) : null, LocalDate.parse(e.path("sessionDate").asText()),
                e.path("reason").asText(null));
        remember(eventId);
        return ResponseEntity.noContent().build();
    }

    // ── settlement, for the back office ──────────────────────────────────────

    @GetMapping("/internal/v1/settlements/{tradeDate}/summary")
    public Map<String, Object> summary(@RequestHeader(value = "X-Service-Key", required = false) String key, @PathVariable LocalDate tradeDate) {
        requireService(key);
        Summary s = settlements.summary(tradeDate);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeDate", s.tradeDate().toString());
        m.put("payable", Money.rupees(s.payable()));
        m.put("receivable", Money.rupees(s.receivable()));
        m.put("closeOuts", Money.rupees(s.closeOuts()));
        m.put("unpostedLedgerEntries", s.unposted());
        m.put("lines", s.lines().stream().map(l -> Map.<String, Object>of("clientCode", l.userId().toString(), "symbol", l.symbol(),
                "bought", l.bought(), "sold", l.sold())).toList());
        return m;
    }

    @PostMapping("/internal/v1/settlements/{tradeDate}/shortages")
    public ResponseEntity<Void> shortages(@RequestHeader(value = "X-Service-Key", required = false) String key, @PathVariable LocalDate tradeDate,
                                          @RequestBody ShortagesRequest req) {
        requireService(key);
        settlements.bookShortages(tradeDate, settlementId(req.settlementId()), req.shortages() == null ? List.of() : req.shortages().stream()
                .map(s -> new Shortage(clientUser(s.clientCode()), s.symbol(), s.quantity() == null ? 0 : s.quantity(), Money.paise(s.closeOutValue())))
                .toList());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/v1/settlements/{tradeDate}/complete")
    public ResponseEntity<Void> complete(@RequestHeader(value = "X-Service-Key", required = false) String key, @PathVariable LocalDate tradeDate,
                                         @RequestBody CompleteRequest req) {
        requireService(key);
        settlements.complete(tradeDate, settlementId(req.settlementId()), req.deliveries() == null ? List.of() : req.deliveries().stream()
                .map(d -> new Delivery(clientUser(d.clientCode()), d.symbol(), d.quantity() == null ? 0 : d.quantity())).toList());
        return ResponseEntity.noContent().build();
    }

    private void requireService(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.serviceKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only Sprout services can call this.");
        }
    }

    private static String settlementId(String id) {
        if (id == null || id.isBlank() || id.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "settlementId is required.");
        }
        return id;
    }

    /** Client codes are the customers' user ids (see accounts: they're registered with clearing that way). */
    private static UUID clientUser(String clientCode) {
        try {
            return UUID.fromString(clientCode);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown client code " + clientCode + ".");
        }
    }

    private void remember(UUID eventId) {
        db.sql("INSERT INTO exchange_events (event_id, received_at) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(eventId, Timestamp.from(clock.instant())).update();
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static UUID userId(String header) {
        if (header == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
        }
    }

    static Map<String, Object> dto(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id().toString());
        m.put("symbol", o.symbol());
        m.put("side", o.side().name());
        m.put("quantity", o.quantity());
        m.put("orderType", o.orderType().name());
        if (o.limitPaise() != null) {
            m.put("limitPrice", Money.rupees(o.limitPaise()));
        }
        m.put("product", o.product().name());
        m.put("variety", o.variety().name());
        m.put("status", o.status());
        m.put("blocked", Money.rupees(o.blockedPaise()));
        if (o.fillPricePaise() != null) {
            m.put("price", Money.rupees(o.fillPricePaise()));
            m.put("value", Money.rupees(o.fillPricePaise() * o.quantity()));
        }
        if (o.charges() != null) {
            Breakdown c = o.charges();
            Map<String, Object> charges = new LinkedHashMap<>();
            charges.put("brokerage", Money.rupees(c.brokerage()));
            charges.put("stt", Money.rupees(c.stt()));
            charges.put("exchangeCharges", Money.rupees(c.exchange()));
            charges.put("sebiFees", Money.rupees(c.sebi()));
            charges.put("stampDuty", Money.rupees(c.stamp()));
            charges.put("gst", Money.rupees(c.gst()));
            charges.put("total", Money.rupees(c.total()));
            m.put("charges", charges);
        }
        if (o.realisedPnlPaise() != null) {
            m.put("realisedPnl", Money.rupees(o.realisedPnlPaise()));
        }
        m.put("autoSquareOff", o.autoSquareOff());
        if (o.rejectionCode() != null) {
            m.put("rejection", Map.of("code", o.rejectionCode(), "message", o.rejectionMessage()));
        }
        if (o.reason() != null) {
            m.put("reason", o.reason());
        }
        if (o.filledAt() != null) {
            m.put("filledAt", o.filledAt().toString());
        }
        m.put("createdAt", o.createdAt().toString());
        m.put("updatedAt", o.updatedAt().toString());
        return m;
    }
}
