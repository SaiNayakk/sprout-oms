package app.sprout.oms;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.oms.domain.LedgerOutbox;
import app.sprout.oms.domain.Rms;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The order service on a real Postgres, against stand-ins for accounts, market data, the ledger (a
 * faithful one: idempotent, balanced, never below zero) and the exchange, each of which the tests
 * can steer or take away. After every test the books must balance and nothing may be left owing
 * the ledger.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=oms", "sprout.oms.rms-every=1h"})
@AutoConfigureMockMvc
class OmsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "dev-only-exchange-webhook-secret";

    // market data
    static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    static final AtomicReference<String> STATE = new AtomicReference<>("OPEN");
    static final AtomicReference<String> TIME = new AtomicReference<>("11:00");
    static final AtomicReference<String> SESSION = new AtomicReference<>("2026-10-05");
    // accounts
    static final Set<String> ACCOUNTS = ConcurrentHashMap.newKeySet();
    // the ledger
    static final Map<String, Long> BALANCES = new ConcurrentHashMap<>();
    static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();
    static final AtomicBoolean LEDGER_DOWN = new AtomicBoolean();
    // the exchange
    static final Map<String, Map<String, Object>> BOOK = new ConcurrentHashMap<>();
    static final AtomicBoolean EXCHANGE_DOWN = new AtomicBoolean();
    static final AtomicBoolean EXCHANGE_LOSES_REPLIES = new AtomicBoolean();
    static final AtomicBoolean EXCHANGE_STALLS = new AtomicBoolean();
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=oms");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.oms.accounts.url", () -> base);
        r.add("sprout.oms.ledger.url", () -> base);
        r.add("sprout.oms.marketdata.url", () -> base);
        r.add("sprout.oms.exchange.url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T05:30:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.OMS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired Rms rms;
    @Autowired LedgerOutbox outbox;
    @Autowired JdbcClient db;

    UUID user;

    @BeforeEach
    void customerWithMoney() {
        LAST.put("HARBOR", 1000_00L);
        LAST.put("INKWELL", 200_00L);
        STATE.set("OPEN");
        TIME.set("11:00");
        SESSION.set("2026-10-05");
        LEDGER_DOWN.set(false);
        EXCHANGE_DOWN.set(false);
        EXCHANGE_LOSES_REPLIES.set(false);
        EXCHANGE_STALLS.set(false);
        user = UUID.randomUUID();
        ACCOUNTS.add(user.toString());
        deposit(user, 10_000_00L);
    }

    @AfterEach
    void theBooksBalanceAndNothingIsLeftUnposted() {
        LEDGER_DOWN.set(false);
        outbox.flush();
        long assets = 0;
        long liabilities = 0;
        for (var e : BALANCES.entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).isNotNegative();
            if (asset(e.getKey())) {
                assets += e.getValue();
            } else {
                liabilities += e.getValue();
            }
        }
        assertThat(assets).as("assets = liabilities").isEqualTo(liabilities);
        assertThat(db.sql("SELECT COUNT(*) FROM ledger_outbox WHERE posted_at IS NULL").query(Integer.class).single()).isZero();
        // what is held for a customer is exactly what their working orders and open positions say
        long held = BALANCES.getOrDefault("customer:" + user + ":order-hold", 0L);
        long blocked = db.sql("SELECT COALESCE(SUM(blocked_paise), 0) FROM orders WHERE user_id = ?").param(user).query(Long.class).single();
        long margins = db.sql("SELECT COALESCE(SUM(margin_paise), 0) FROM positions WHERE user_id = ?").param(user).query(Long.class).single();
        assertThat(held).as("order-hold").isEqualTo(blocked + margins);
    }

    static void deposit(UUID u, long paise) {
        BALANCES.merge("sprout:bank", paise, Long::sum);
        BALANCES.merge("customer:" + u + ":cash", paise, Long::sum);
    }

    static long balance(String account) {
        return BALANCES.getOrDefault(account, 0L);
    }

    String cash() {
        return rupees(balance("customer:" + user + ":cash"));
    }

    static String rupees(long p) {
        return (p < 0 ? "-" : "") + Math.abs(p) / 100 + "." + String.format("%02d", Math.abs(p) % 100);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    ResultActions place(String key, String symbol, String side, int qty, String type, String limit, String product, String variety) throws Exception {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("symbol", symbol);
        o.put("side", side);
        o.put("quantity", qty);
        o.put("orderType", type);
        if (limit != null) {
            o.put("limitPrice", limit);
        }
        o.put("product", product);
        if (variety != null) {
            o.put("variety", variety);
        }
        return mvc.perform(post("/v1/orders").header("X-User-Id", user.toString()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(o)));
    }

    JsonNode order(String side, int qty, String type, String limit, String product) throws Exception {
        return body(place(UUID.randomUUID().toString(), "HARBOR", side, qty, type, limit, product, null)
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
    }

    JsonNode market(String side, int qty, String product) throws Exception {
        return order(side, qty, "MARKET", null, product);
    }

    JsonNode fetch(JsonNode order) throws Exception {
        return body(mvc.perform(mine("/v1/orders/" + order.path("id").asText())).andExpect(MATCHES_CONTRACT));
    }

    org.springframework.test.web.servlet.RequestBuilder mine(String path) {
        return get(path).header("X-User-Id", user.toString());
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    JsonNode funds() throws Exception {
        return body(mvc.perform(mine("/v1/funds")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
    }

    JsonNode positions() throws Exception {
        return body(mvc.perform(mine("/v1/positions")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT))
                .path("positions");
    }

    JsonNode holdings() throws Exception {
        return body(mvc.perform(mine("/v1/holdings")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT))
                .path("holdings");
    }

    ResultActions callback(Map<String, Object> event) throws Exception {
        String body = JSON.writeValueAsString(event);
        return mvc.perform(post("/internal/v1/exchange-events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Exchange-Signature", "sha256=" + sign(body)).content(body));
    }

    /** A map of any size, in order: key, value, key, value ... */
    static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    @Test
    void aDeliveryBuyBlocksItsMoneyThenPaysTheRealPriceAndChargesAndGivesBackTheRest() throws Exception {
        long owedToClearing = balance("sprout:clearing-payable");
        JsonNode o = market("BUY", 5, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("FILLED");
        assertThat(o.path("price").asText()).isEqualTo("1000.00");
        assertThat(o.path("value").asText()).isEqualTo("5000.00");
        assertThat(o.path("charges").path("total").asText()).isEqualTo("5.94");   // STT 5, stamp 0.75, exchange 0.15, SEBI 0.01, GST 0.03
        assertThat(o.path("blocked").asText()).isEqualTo("0.00");
        assertThat(cash()).isEqualTo("4994.06");
        JsonNode h = holdings().get(0);
        assertThat(h.path("quantity").asInt()).isEqualTo(5);
        assertThat(h.path("t1Quantity").asInt()).isEqualTo(5);
        assertThat(h.path("averagePrice").asText()).isEqualTo("1000.00");
        JsonNode f = funds();
        assertThat(f.path("availableToTrade").asText()).isEqualTo("4994.06");
        assertThat(f.path("blocked").asText()).isEqualTo("0.00");
        assertThat(balance("sprout:clearing-payable") - owedToClearing).isEqualTo(5000_00);
    }

    @Test
    void aLimitOrderKeepsItsMoneyBlockedUntilItExecutesAndAnExecutionIsBookedOnce() throws Exception {
        JsonNode o = order("BUY", 2, "LIMIT", "990.00", "CNC");
        assertThat(o.path("status").asText()).isEqualTo("OPEN");
        assertThat(o.path("blocked").asText()).isEqualTo("1982.37");   // 1980 + its charges
        assertThat(cash()).isEqualTo("8017.63");
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> o2 = BOOK.get(o.path("id").asText());
        o2.put("status", "FILLED");
        o2.put("price", "989.50");
        o2.put("tradeId", UUID.randomUUID().toString());
        Map<String, Object> event = fields("eventId", eventId, "type", "ORDER_FILLED", "clientOrderId", o.path("id").asText(),
                "exchangeOrderId", o2.get("exchangeOrderId"), "symbol", "HARBOR", "side", "BUY", "quantity", 2, "price", "989.50",
                "tradeId", o2.get("tradeId"), "sessionDate", "2026-10-05", "occurredAt", "2026-10-05T05:31:00Z");
        callback(event).andExpect(status().isNoContent());
        callback(event).andExpect(status().isNoContent());                      // delivered twice
        callback(fields("eventId", UUID.randomUUID().toString(), "type", "ORDER_FILLED", "clientOrderId", o.path("id").asText(),
                "exchangeOrderId", o2.get("exchangeOrderId"), "symbol", "HARBOR", "side", "BUY", "quantity", 2, "price", "989.50",
                "tradeId", o2.get("tradeId"), "sessionDate", "2026-10-05", "occurredAt", "2026-10-05T05:31:00Z")).andExpect(status().isNoContent());   // and once more, re-sent
        assertThat(fetch(o).path("status").asText()).isEqualTo("FILLED");
        assertThat(holdings().get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(cash()).isEqualTo(rupees(10_000_00 - 1979_00 - 2_37));       // value 1979 and its charges, once
        mvc.perform(post("/internal/v1/exchange-events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Exchange-Signature", "sha256=" + "0".repeat(64)).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void cancellingGivesTheMoneyBackAndAnExecutedOrderCantBeCancelled() throws Exception {
        JsonNode open = order("BUY", 3, "LIMIT", "950.00", "CNC");
        mvc.perform(delete("/v1/orders/" + open.path("id").asText()).header("X-User-Id", user.toString()))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(cash()).isEqualTo("10000.00");
        JsonNode filled = market("BUY", 1, "CNC");
        mvc.perform(delete("/v1/orders/" + filled.path("id").asText()).header("X-User-Id", user.toString()))
                .andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
        mvc.perform(delete("/v1/orders/" + filled.path("id").asText()).header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nearTheTopOfTheDaysBandAMarketOrdersProtectionStopsAtTheBand() throws Exception {
        LAST.put("HARBOR", 1190_00L);   // 19% up: 3% protection would be past the 20% band
        JsonNode o = market("BUY", 1, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("FILLED");
        assertThat(o.path("price").asText()).isEqualTo("1190.00");
        assertThat(db.sql("SELECT protection_paise FROM orders WHERE id = ?").param(UUID.fromString(o.path("id").asText()))
                .query(Long.class).single()).isEqualTo(1200_00L);
    }

    @Test
    void anOrderBeyondTheMoneyIsRejectedAndBlocksNothing() throws Exception {
        JsonNode o = market("BUY", 20, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("REJECTED");
        assertThat(o.path("rejection").path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(o.path("rejection").path("message").asText()).contains("10000.00");
        assertThat(cash()).isEqualTo("10000.00");
    }

    @Test
    void youCanOnlySellWhatYouHoldAndTheProceedsWaitForSettlement() throws Exception {
        market("BUY", 4, "CNC");
        JsonNode tooMany = market("SELL", 5, "CNC");
        assertThat(tooMany.path("rejection").path("code").asText()).isEqualTo("INSUFFICIENT_HOLDINGS");
        order("SELL", 3, "LIMIT", "1100.00", "CNC");                              // resting: 3 of the 4 are spoken for
        assertThat(market("SELL", 2, "CNC").path("rejection").path("code").asText()).isEqualTo("INSUFFICIENT_HOLDINGS");
        LAST.put("HARBOR", 1050_00L);
        JsonNode sold = market("SELL", 1, "CNC");
        assertThat(sold.path("status").asText()).isEqualTo("FILLED");
        assertThat(sold.path("realisedPnl").asText()).isEqualTo("50.00");
        JsonNode f = funds();
        assertThat(f.path("unsettled").asText()).isEqualTo(rupees(1050_00 - Long.parseLong(sold.path("charges").path("total").asText().replace(".", ""))));
        assertThat(holdings().get(0).path("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void sendingTheSameOrderTwiceIsOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = body(place(key, "HARBOR", "BUY", 1, "MARKET", null, "CNC", null).andExpect(status().isCreated())).path("id").asText();
        place(key, "HARBOR", "BUY", 1, "MARKET", null, "CNC", null).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.id").value(id));
        place(key, "HARBOR", "BUY", 2, "MARKET", null, "CNC", null).andExpect(status().isBadRequest());
        assertThat(holdings().get(0).path("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void requestsThatAreWrongAreProblemsNotOrders() throws Exception {
        place(UUID.randomUUID().toString(), "NOPE", "BUY", 1, "MARKET", null, "CNC", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
        place(UUID.randomUUID().toString(), "SPROUT20", "BUY", 1, "MARKET", null, "CNC", null)
                .andExpect(status().isUnprocessableEntity());
        place(UUID.randomUUID().toString(), "HARBOR", "BUY", 0, "MARKET", null, "CNC", null)
                .andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
        place(UUID.randomUUID().toString(), "HARBOR", "BUY", 1, "LIMIT", null, "CNC", null).andExpect(status().isBadRequest());
        assertThat(order("BUY", 1, "LIMIT", "1000.03", "CNC").path("rejection").path("code").asText()).isEqualTo("INVALID_TICK");
        assertThat(order("BUY", 1, "LIMIT", "1250.00", "CNC").path("rejection").path("code").asText()).isEqualTo("PRICE_OUT_OF_BAND");
        UUID stranger = UUID.randomUUID();
        mvc.perform(post("/v1/orders").header("X-User-Id", stranger.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"HARBOR\",\"side\":\"BUY\",\"quantity\":1,\"orderType\":\"MARKET\",\"product\":\"CNC\"}"))
                .andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    // ── intraday ─────────────────────────────────────────────────────────────

    @Test
    void anIntradayLongNeedsAFifthAsMarginAndItsProfitIsBookedWhenClosed() throws Exception {
        JsonNode buy = market("BUY", 10, "MIS");
        assertThat(buy.path("status").asText()).isEqualTo("FILLED");
        JsonNode p = positions().get(0);
        assertThat(p.path("quantity").asInt()).isEqualTo(10);
        assertThat(p.path("margin").asText()).isEqualTo("2000.00");
        long buyCharges = paise(buy.path("charges").path("total").asText());
        assertThat(cash()).isEqualTo(rupees(10_000_00 - 2000_00 - buyCharges));
        LAST.put("HARBOR", 1010_00L);
        assertThat(positions().get(0).path("unrealisedPnl").asText()).isEqualTo("100.00");
        JsonNode sell = market("SELL", 10, "MIS");
        assertThat(sell.path("realisedPnl").asText()).isEqualTo("100.00");
        long sellCharges = paise(sell.path("charges").path("total").asText());
        p = positions().get(0);
        assertThat(p.path("quantity").asInt()).isZero();
        assertThat(p.path("realisedPnl").asText()).isEqualTo("100.00");
        assertThat(cash()).isEqualTo(rupees(10_000_00 - buyCharges - sellCharges));
        assertThat(funds().path("unsettled").asText()).isEqualTo("100.00");
    }

    @Test
    void intradayLetsYouSellFirstAndBuyBackButNotFlipInOneOrder() throws Exception {
        JsonNode shortSale = market("SELL", 10, "MIS");
        assertThat(shortSale.path("status").asText()).isEqualTo("FILLED");
        assertThat(positions().get(0).path("quantity").asInt()).isEqualTo(-10);
        assertThat(market("BUY", 15, "MIS").path("rejection").path("code").asText()).isEqualTo("POSITION_FLIP");
        LAST.put("HARBOR", 990_00L);
        JsonNode cover = market("BUY", 10, "MIS");
        assertThat(cover.path("realisedPnl").asText()).isEqualTo("100.00");
        assertThat(market("SELL", 1, "CNC").path("rejection").path("code").asText()).isEqualTo("INSUFFICIENT_HOLDINGS");   // no delivery shorting
    }

    @Test
    void atSquareOffTimeSproutClosesIntradayPositionsForAFeeAndStopsNewOnes() throws Exception {
        market("BUY", 10, "MIS");
        JsonNode resting = order("BUY", 5, "LIMIT", "950.00", "MIS");
        TIME.set("15:20");
        rms.round();
        assertThat(fetch(resting).path("status").asText()).isEqualTo("CANCELLED");
        JsonNode p = positions().get(0);
        assertThat(p.path("quantity").asInt()).isZero();
        JsonNode auto = null;
        for (JsonNode o : body(mvc.perform(mine("/v1/orders")).andExpect(MATCHES_CONTRACT)).path("orders")) {
            if (o.path("autoSquareOff").asBoolean()) {
                auto = o;
            }
        }
        assertThat(auto).isNotNull();
        assertThat(auto.path("autoSquareOff").asBoolean()).isTrue();
        assertThat(auto.path("status").asText()).isEqualTo("FILLED");
        assertThat(auto.path("charges").path("brokerage").asText()).isEqualTo("53.00");   // ₹3 (0.03%) + ₹50 square-off
        assertThat(market("BUY", 1, "MIS").path("rejection").path("code").asText()).isEqualTo("INTRADAY_CLOSED");
        assertThat(market("BUY", 1, "CNC").path("status").asText()).isEqualTo("FILLED");                    // delivery still fine
    }

    @Test
    void aPositionWhoseLossEatsNinetyPercentOfItsMarginIsClosedEarly() throws Exception {
        market("BUY", 10, "MIS");
        LAST.put("HARBOR", 830_00L);    // loss 1700: 85% of the 2000 margin
        rms.round();
        assertThat(positions().get(0).path("quantity").asInt()).isEqualTo(10);
        LAST.put("HARBOR", 815_00L);    // loss 1850: 92.5%
        rms.round();
        assertThat(positions().get(0).path("quantity").asInt()).isZero();
        assertThat(positions().get(0).path("realisedPnl").asText()).isEqualTo("-1850.00");
    }

    @Test
    void aLossBeyondTheCustomersMoneyBecomesDuesRecoveredFromTheirNextDeposit() throws Exception {
        UUID poor = UUID.randomUUID();
        ACCOUNTS.add(poor.toString());
        deposit(poor, 2100_00L);
        UUID me = user;
        user = poor;
        JsonNode buy = market("BUY", 10, "MIS");        // margin 2000 and charges: almost everything
        assertThat(buy.path("status").asText()).isEqualTo("FILLED");
        LAST.put("HARBOR", 700_00L);                    // a 30% gap down: the loss is 3000
        rms.round();
        assertThat(positions().get(0).path("quantity").asInt()).isZero();
        JsonNode f = funds();
        assertThat(paise(f.path("dues").asText())).isPositive();   // what the cash couldn't cover
        long owed = paise(f.path("dues").asText());
        deposit(poor, 5000_00L);
        rms.round();
        assertThat(funds().path("dues").asText()).isEqualTo("0.00");
        assertThat(balance("customer:" + poor + ":cash")).isEqualTo(5000_00L + paise(f.path("cash").asText()) - owed);
        theBooksBalanceAndNothingIsLeftUnposted();
        user = me;
    }

    // ── settlement ───────────────────────────────────────────────────────────

    JsonNode summary(String day) throws Exception {
        return body(mvc.perform(get("/internal/v1/settlements/" + day + "/summary").header("X-Service-Key", "dev-only-service-key"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
    }

    ResultActions internal(String path, Object body) throws Exception {
        return mvc.perform(post(path).header("X-Service-Key", "dev-only-service-key").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    @Test
    void aSettledDayMakesSaleProceedsCashAndBoughtSharesDelivered() throws Exception {
        String day = "2026-10-07";   // a trade date of this test's own
        SESSION.set(day);
        market("BUY", 5, "CNC");                       // pays 5,000
        clock.advance(Duration.ofSeconds(1));          // executions a second apart, as real ones are
        LAST.put("HARBOR", 1050_00L);
        JsonNode sold = market("SELL", 2, "CNC");      // gets 2,100 less charges, unsettled
        clock.advance(Duration.ofSeconds(1));
        market("BUY", 10, "MIS");
        clock.advance(Duration.ofSeconds(1));
        LAST.put("HARBOR", 1060_00L);
        market("SELL", 10, "MIS");                     // intraday profit 100, unsettled
        outbox.flush();

        JsonNode s = summary(day);
        assertThat(s.path("payable").asText()).isEqualTo("5000.00");
        assertThat(s.path("receivable").asText()).isEqualTo("2200.00");
        assertThat(s.path("unpostedLedgerEntries").asInt()).isZero();
        JsonNode line = s.path("lines").get(0);
        assertThat(line.path("clientCode").asText()).isEqualTo(user.toString());
        assertThat(line.path("bought").asLong()).isEqualTo(15);
        assertThat(line.path("sold").asLong()).isEqualTo(12);

        // a short delivery is charged to the client, once
        Map<String, Object> shortage = Map.of("settlementId", "scc-test-1", "shortages",
                List.of(Map.of("clientCode", user.toString(), "symbol", "HARBOR", "quantity", 1, "closeOutValue", "600.00")));
        internal("/internal/v1/settlements/" + day + "/shortages", shortage).andExpect(status().isNoContent());
        internal("/internal/v1/settlements/" + day + "/shortages", shortage).andExpect(status().isNoContent());
        assertThat(funds().path("dues").asText()).isEqualTo("600.00");
        assertThat(summary(day).path("payable").asText()).isEqualTo("5600.00");

        long unsettled = paise(funds().path("unsettled").asText());
        long cashBefore = paise(funds().path("cash").asText());
        assertThat(unsettled).isEqualTo(2100_00 - paise(sold.path("charges").path("total").asText()) + 100_00);
        Map<String, Object> done = Map.of("settlementId", "scc-test-1", "deliveries",
                List.of(Map.of("clientCode", user.toString(), "symbol", "HARBOR", "quantity", 3)));
        internal("/internal/v1/settlements/" + day + "/complete", done).andExpect(status().isNoContent());
        internal("/internal/v1/settlements/" + day + "/complete", done).andExpect(status().isNoContent());
        rms.round();   // dues are recovered from the cash now there is some
        JsonNode f = funds();
        assertThat(f.path("unsettled").asText()).isEqualTo("0.00");
        assertThat(f.path("dues").asText()).isEqualTo("0.00");
        assertThat(paise(f.path("cash").asText())).isEqualTo(cashBefore + unsettled - 600_00);
        JsonNode h = holdings().get(0);
        assertThat(h.path("quantity").asInt()).isEqualTo(3);
        assertThat(h.path("t1Quantity").asInt()).as("delivered").isZero();
        mvc.perform(get("/internal/v1/settlements/" + day + "/summary")).andExpect(status().isUnauthorized());

        // the records other services report from
        JsonNode executions = body(mvc.perform(get("/internal/v1/executions").param("from", day).param("to", day).param("userId", user.toString())
                .header("X-Service-Key", "dev-only-service-key")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("executions");
        assertThat(executions.size()).isEqualTo(4);
        assertThat(executions.get(0).path("side").asText()).isEqualTo("BUY");
        assertThat(executions.get(3).path("realisedPnl").asText()).isEqualTo("100.00");
        JsonNode me = null;
        for (JsonNode c : body(mvc.perform(get("/internal/v1/recon").header("X-Service-Key", "dev-only-service-key"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)).path("customers")) {
            if (c.path("userId").asText().equals(user.toString())) {
                me = c;
            }
        }
        assertThat(me).isNotNull();
        assertThat(me.path("unsettled").asText()).as("the day is settled").isEqualTo("0.00");
        assertThat(me.path("delivered").get(0).path("quantity").asLong()).isEqualTo(3);
    }

    // ── when the market is closed ────────────────────────────────────────────

    @Test
    void whileClosedOnlyAfterMarketOrdersAreTakenAndTheyGoOutAtTheOpen() throws Exception {
        STATE.set("CLOSED");
        TIME.set("18:00");
        assertThat(market("BUY", 1, "CNC").path("rejection").path("code").asText()).isEqualTo("MARKET_CLOSED");
        JsonNode amo = body(place(UUID.randomUUID().toString(), "HARBOR", "BUY", 2, "MARKET", null, "CNC", "AMO")
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        assertThat(amo.path("status").asText()).isEqualTo("AMO_QUEUED");
        assertThat(paise(amo.path("blocked").asText())).isGreaterThan(2000_00);
        rms.round();
        assertThat(fetch(amo).path("status").asText()).isEqualTo("AMO_QUEUED");
        STATE.set("OPEN");
        TIME.set("09:15");
        rms.round();
        assertThat(fetch(amo).path("status").asText()).isEqualTo("FILLED");
        assertThat(body(place(UUID.randomUUID().toString(), "HARBOR", "BUY", 1, "MARKET", null, "CNC", "AMO"))
                .path("rejection").path("code").asText()).isEqualTo("MARKET_OPEN");
    }

    // ── when something is away ───────────────────────────────────────────────

    @Test
    void anOrderTheExchangeNeverGotIsRejectedAndItsMoneyReleased() throws Exception {
        EXCHANGE_DOWN.set(true);
        JsonNode o = market("BUY", 1, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("PENDING");
        assertThat(paise(o.path("blocked").asText())).isPositive();
        EXCHANGE_DOWN.set(false);
        // the risk desk's real pace: a round every second, asking the exchange about it every 10 s
        for (int second = 1; second <= 25; second++) {
            clock.advance(Duration.ofSeconds(1));
            rms.round();
        }
        assertThat(fetch(o).path("status").asText()).isEqualTo("PENDING");     // not given up on yet
        for (int second = 26; second <= 45 && fetch(o).path("status").asText().equals("PENDING"); second++) {
            clock.advance(Duration.ofSeconds(1));
            rms.round();
        }
        assertThat(fetch(o).path("status").asText()).isEqualTo("REJECTED");
        assertThat(fetch(o).path("rejection").path("code").asText()).isEqualTo("UNAVAILABLE");
        assertThat(cash()).isEqualTo("10000.00");
    }

    @Test
    void aStallingExchangeNeverKeepsTheCustomerWaitingAndTheOrderIsSettledLater() throws Exception {
        EXCHANGE_STALLS.set(true);
        long start = System.nanoTime();
        JsonNode o = market("BUY", 2, "CNC");
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(tookMs).as("answered inside the gateway's 5 s").isLessThan(3500);
        assertThat(o.path("status").asText()).isEqualTo("PENDING");
        EXCHANGE_STALLS.set(false);
        Thread.sleep(5000);                         // the stalled exchange finishes executing it
        clock.advance(Duration.ofSeconds(15));
        rms.round();
        assertThat(fetch(o).path("status").asText()).isEqualTo("FILLED");
        assertThat(holdings().get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void anExecutionWhoseReplyWasLostIsFoundAndBookedOnce() throws Exception {
        EXCHANGE_LOSES_REPLIES.set(true);
        JsonNode o = market("BUY", 3, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("PENDING");
        EXCHANGE_LOSES_REPLIES.set(false);
        clock.advance(Duration.ofSeconds(15));
        rms.round();
        rms.round();
        assertThat(fetch(o).path("status").asText()).isEqualTo("FILLED");
        assertThat(holdings().get(0).path("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void aHoldWhoseAnswerWasLostEndsAtZeroWhenTheLedgerIsBack() throws Exception {
        LEDGER_DOWN.set(true);
        JsonNode o = market("BUY", 1, "CNC");
        assertThat(o.path("status").asText()).isEqualTo("REJECTED");
        assertThat(o.path("rejection").path("code").asText()).isEqualTo("UNAVAILABLE");
        LEDGER_DOWN.set(false);
        rms.round();
        assertThat(cash()).isEqualTo("10000.00");
        assertThat(balance("customer:" + user + ":order-hold")).isZero();
    }

    @Test
    void racingSellsCanNeverSellTheSameSharesTwice() throws Exception {
        market("BUY", 5, "CNC");
        List<Thread> racers = new ArrayList<>();
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 6; i++) {
            racers.add(Thread.ofVirtual().start(() -> {
                try {
                    outcomes.add(body(place(UUID.randomUUID().toString(), "HARBOR", "SELL", 2, "LIMIT", "1100.00", "CNC", null)).path("status").asText());
                } catch (Exception e) {
                    outcomes.add(e.toString());
                }
            }));
        }
        for (Thread t : racers) {
            t.join();
        }
        assertThat(outcomes.stream().filter("OPEN"::equals).count()).isEqualTo(2);
        assertThat(outcomes.stream().filter("REJECTED"::equals).count()).isEqualTo(4);
    }

    static long paise(String rupees) {
        boolean negative = rupees.startsWith("-");
        String r = negative ? rupees.substring(1) : rupees;
        long p = Long.parseLong(r.replace(".", ""));
        return negative ? -p : p;
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static boolean asset(String account) {
        return account.equals("sprout:bank") || account.equals("sprout:clearing-receivable") || account.endsWith(":dues");
    }

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            s.createContext("/internal/v1/accounts/", ex -> {
                String id = ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length());
                if (ACCOUNTS.contains(id)) {
                    reply(ex, 200, Map.of("userId", id, "status", "ACTIVE"));
                } else {
                    reply(ex, 404, Map.of("code", "NO_ACCOUNT"));
                }
            });
            s.createContext("/v1/instruments", ex -> reply(ex, 200, Map.of("instruments", List.of(
                    Map.of("symbol", "HARBOR", "tickSize", 0.05, "tradable", true),
                    Map.of("symbol", "INKWELL", "tickSize", 0.05, "tradable", true),
                    Map.of("symbol", "SPROUT20", "tickSize", 0.01, "tradable", false)))));
            s.createContext("/v1/market", ex -> reply(ex, 200, market()));
            s.createContext("/v1/quotes", ex -> {
                List<Map<String, Object>> quotes = new ArrayList<>();
                for (String sym : java.net.URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8).replace("symbols=", "").split(",")) {
                    quotes.add(Map.of("symbol", sym, "last", LAST.get(sym) / 100.0, "prevClose", sym.equals("HARBOR") ? 1000.0 : 200.0));
                }
                reply(ex, 200, Map.of("market", market(), "quotes", quotes));
            });
            s.createContext("/v1/journal-entries", OmsApiTest::ledgerPost);
            s.createContext("/v1/accounts/", ex -> {
                if (LEDGER_DOWN.get()) {
                    reply(ex, 503, Map.of());
                    return;
                }
                String account = java.net.URLDecoder.decode(ex.getRequestURI().getRawPath().substring("/v1/accounts/".length()), StandardCharsets.UTF_8);
                reply(ex, 200, Map.of("account", account, "balance", rupees(balance(account))));
            });
            s.createContext("/member/v1/orders", OmsApiTest::exchange);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> market() {
        return Map.of("state", STATE.get(), "sessionDate", SESSION.get(), "marketTime", SESSION.get() + "T" + TIME.get() + ":00+05:30");
    }

    static synchronized void ledgerPost(HttpExchange ex) throws IOException {
        if (LEDGER_DOWN.get()) {
            reply(ex, 503, Map.of());
            return;
        }
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode e = JSON.readTree(raw);
        String key = e.path("idempotencyKey").asText();
        String canonical = JSON.writeValueAsString(e.path("postings"));
        if (ENTRIES.containsKey(key)) {
            reply(ex, ENTRIES.get(key).equals(canonical) ? 200 : 409, Map.of("code", ENTRIES.get(key).equals(canonical) ? "" : "IDEMPOTENCY_CONFLICT"));
            return;
        }
        Map<String, Long> delta = new LinkedHashMap<>();
        long debits = 0;
        long credits = 0;
        for (JsonNode p : e.path("postings")) {
            String account = p.path("account").asText();
            long amount = paise(p.path("amount").asText());
            boolean debit = p.path("side").asText().equals("DEBIT");
            if (debit) {
                debits += amount;
            } else {
                credits += amount;
            }
            delta.merge(account, (asset(account) == debit) ? amount : -amount, Long::sum);
        }
        if (debits != credits) {
            reply(ex, 422, Map.of("code", "UNBALANCED"));
            return;
        }
        for (var d : delta.entrySet()) {
            if (balance(d.getKey()) + d.getValue() < 0) {
                reply(ex, 422, Map.of("code", "INSUFFICIENT_FUNDS"));
                return;
            }
        }
        delta.forEach((a, d) -> BALANCES.merge(a, d, Long::sum));
        ENTRIES.put(key, canonical);
        reply(ex, 201, Map.of("id", UUID.randomUUID().toString()));
    }

    static void exchange(HttpExchange ex) throws IOException {
        if (EXCHANGE_DOWN.get()) {
            reply(ex, 503, Map.of());
            return;
        }
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (method.equals("POST")) {
            JsonNode o = JSON.readTree(ex.getRequestBody().readAllBytes());
            if (!STATE.get().equals("OPEN")) {
                reply(ex, 422, Map.of("code", "MARKET_CLOSED", "detail", "closed"));
                return;
            }
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("exchangeOrderId", UUID.randomUUID().toString());
            order.put("clientOrderId", o.path("clientOrderId").asText());
            order.put("symbol", o.path("symbol").asText());
            order.put("side", o.path("side").asText());
            order.put("type", o.path("type").asText());
            order.put("quantity", o.path("quantity").asInt());
            order.put("filledQuantity", 0);
            order.put("sessionDate", SESSION.get());
            long last = LAST.get(o.path("symbol").asText());
            boolean buy = o.path("side").asText().equals("BUY");
            boolean fills;
            if (o.path("type").asText().equals("MARKET")) {
                fills = true;
                if (o.hasNonNull("protectionPrice")) {
                    long protection = paise(o.path("protectionPrice").asText());
                    if (buy ? last > protection : last < protection) {
                        order.put("status", "CANCELLED");
                        fills = false;
                    }
                }
            } else {
                long limit = paise(o.path("limitPrice").asText());
                fills = buy ? last <= limit : last >= limit;
            }
            if (fills) {
                order.put("status", "FILLED");
                order.put("price", rupees(last));
                order.put("tradeId", UUID.randomUUID().toString());
                order.put("filledQuantity", o.path("quantity").asInt());
            } else {
                order.putIfAbsent("status", "OPEN");
            }
            BOOK.put(o.path("clientOrderId").asText(), order);
            if (EXCHANGE_STALLS.get()) {
                try {
                    Thread.sleep(6000);   // longer than anyone should wait
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (EXCHANGE_LOSES_REPLIES.get()) {
                reply(ex, 503, Map.of());
                return;
            }
            reply(ex, 201, order);
            return;
        }
        String id = path.substring("/member/v1/orders/".length());
        Map<String, Object> order = BOOK.get(id);
        if (order == null) {
            reply(ex, 404, Map.of("code", "NOT_FOUND"));
            return;
        }
        if (method.equals("DELETE")) {
            if (order.get("status").equals("OPEN")) {
                order.put("status", "CANCELLED");
                order.put("reason", "Cancelled by the member.");
            } else if (!order.get("status").equals("CANCELLED")) {
                reply(ex, 409, Map.of("code", "ORDER_NOT_OPEN"));
                return;
            }
        }
        reply(ex, 200, order);
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
