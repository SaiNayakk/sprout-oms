package app.sprout.oms.domain;

import app.sprout.oms.config.OmsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The services the order service talks to: accounts (is this customer allowed to trade), market
 * data (prices and trading hours), the ledger (the money) and the exchange. Plain HTTP, each call
 * with a hard deadline that covers everything (name lookup, connecting, the answer): 3 s, and 2 s
 * for the exchange, so a customer placing an order hears back well inside the gateway's 5 s even
 * when the exchange has vanished. No answer, or a 5xx, is {@link Unreachable}: the outcome is
 * unknown, and callers decide what that means for them.
 */
@Component
public class Upstreams {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static class Unreachable extends RuntimeException {
        public Unreachable(String what, Throwable cause) {
            super(what, cause);
        }
    }

    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String code() {
            return body == null ? "" : body.path("code").asText();
        }
    }

    public record Instrument(String symbol, long tickPaise, boolean tradable) {}

    /** The market as the order service needs it: open or not, which session, the time there, a price. */
    public record Market(boolean open, LocalDate sessionDate, LocalTime time) {}

    public record Quote(Market market, long lastPaise, long prevClosePaise) {}

    public record Leg(String account, String side, long paise) {}

    private final OmsProperties props;
    private final ObjectMapper json;
    static final Duration DEADLINE = Duration.ofSeconds(3);
    static final Duration EXCHANGE_DEADLINE = Duration.ofSeconds(2);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;
    private final Map<String, Instrument> instruments = new ConcurrentHashMap<>();

    public Upstreams(OmsProperties props, ObjectMapper json, Onward onward) {
        this.onward = onward;
        this.props = props;
        this.json = json;
    }

    // ── accounts ─────────────────────────────────────────────────────────────

    /** Throws NO_ACCOUNT unless the customer has an open Sprout account. */
    public void requireAccount(UUID userId) {
        Reply r = send("accounts", HttpRequest.newBuilder(URI.create(props.accounts().url() + "/internal/v1/accounts/" + userId))
                .header("X-Service-Key", props.accounts().serviceKey()).GET());
        if (r.status() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        if (!r.ok()) {
            throw unavailable();
        }
        if (!"ACTIVE".equals(r.body().path("status").asText("ACTIVE"))) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Your Sprout account isn't active.");
        }
    }

    // ── market data ──────────────────────────────────────────────────────────

    public Optional<Instrument> instrument(String symbol) {
        Instrument known = instruments.get(symbol);
        if (known != null) {
            return Optional.of(known);
        }
        Reply r = send("marketdata", HttpRequest.newBuilder(URI.create(props.marketdata().url() + "/v1/instruments")).GET());
        if (!r.ok()) {
            throw unavailable();
        }
        for (JsonNode i : r.body().path("instruments")) {
            long tick = Math.max(1, Math.round(i.path("tickSize").asDouble() * 100));
            instruments.put(i.path("symbol").asText(), new Instrument(i.path("symbol").asText(), tick, i.path("tradable").asBoolean()));
        }
        return Optional.ofNullable(instruments.get(symbol));
    }

    public Market market() {
        Reply r = send("marketdata", HttpRequest.newBuilder(URI.create(props.marketdata().url() + "/v1/market")).GET());
        if (!r.ok()) {
            throw new Unreachable("marketdata answered " + r.status(), null);
        }
        return market(r.body());
    }

    /** Prices for several symbols at once, with the market they were read in. */
    public Map<String, Quote> quotes(List<String> symbols) {
        Map<String, Quote> out = new ConcurrentHashMap<>();
        for (int from = 0; from < symbols.size(); from += 25) {
            String csv = String.join(",", symbols.subList(from, Math.min(symbols.size(), from + 25)));
            Reply r = send("marketdata", HttpRequest.newBuilder(URI.create(props.marketdata().url() + "/v1/quotes?symbols="
                    + URLEncoder.encode(csv, StandardCharsets.UTF_8))).GET());
            if (!r.ok()) {
                throw new Unreachable("marketdata answered " + r.status(), null);
            }
            Market m = market(r.body().path("market"));
            for (JsonNode q : r.body().path("quotes")) {
                out.put(q.path("symbol").asText(), new Quote(m, Math.round(q.path("last").asDouble() * 100),
                        Math.round(q.path("prevClose").asDouble() * 100)));
            }
        }
        return out;
    }

    private static Market market(JsonNode m) {
        LocalTime time = OffsetDateTime.parse(m.path("marketTime").asText()).atZoneSameInstant(IST).toLocalTime();
        return new Market("OPEN".equals(m.path("state").asText()), LocalDate.parse(m.path("sessionDate").asText()), time);
    }

    // ── ledger ───────────────────────────────────────────────────────────────

    /** Posts an entry; 201 and 200 (posted before under this key) are both success. */
    public Reply post(String key, String description, String reference, List<Leg> legs) {
        Map<String, Object> body = Map.of("idempotencyKey", key, "description", description, "reference", reference,
                "postings", legs.stream().map(l -> Map.of("account", l.account(), "side", l.side(), "amount", Money.rupees(l.paise()))).toList());
        return postBody(write(body));
    }

    public Reply postBody(String body) {
        return send("ledger", HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/journal-entries"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public long balance(String account) {
        Reply r = send("ledger", HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/accounts/"
                + URLEncoder.encode(account, StandardCharsets.UTF_8))).GET());
        if (!r.ok()) {
            throw new Unreachable("ledger answered " + r.status(), null);
        }
        return Money.paise(r.body().path("balance").asText());
    }

    // ── exchange ─────────────────────────────────────────────────────────────

    public Reply placeOnExchange(Map<String, Object> order) {
        return send("exchange", EXCHANGE_DEADLINE, HttpRequest.newBuilder(URI.create(props.exchange().url() + "/member/v1/orders"))
                .header("Content-Type", "application/json").header("X-Member-Key", props.exchange().memberKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(order))));
    }

    public Reply exchangeOrder(UUID orderId) {
        return send("exchange", EXCHANGE_DEADLINE, HttpRequest.newBuilder(URI.create(props.exchange().url() + "/member/v1/orders/" + orderId))
                .header("X-Member-Key", props.exchange().memberKey()).GET());
    }

    public Reply cancelOnExchange(UUID orderId) {
        return send("exchange", EXCHANGE_DEADLINE, HttpRequest.newBuilder(URI.create(props.exchange().url() + "/member/v1/orders/" + orderId))
                .header("X-Member-Key", props.exchange().memberKey()).DELETE());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private Reply send(String what, HttpRequest.Builder req) {
        return send(what, DEADLINE, req);
    }

    private Reply send(String what, Duration deadline, HttpRequest.Builder req) {
        onward.headers(req);
        try {
            // sendAsync + get: the deadline holds even while the host's name is being looked up
            HttpResponse<String> res = http.sendAsync(req.timeout(deadline).build(), HttpResponse.BodyHandlers.ofString())
                    .get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode(), null);
            }
            JsonNode body = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
            return new Reply(res.statusCode(), body);
        } catch (Unreachable e) {
            throw e;
        } catch (TimeoutException e) {
            throw new Unreachable(what + " didn't answer within " + deadline.toMillis() + " ms", e);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new Unreachable(what + " unreachable: " + cause.getClass().getSimpleName(), cause);
        }
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout isn't reachable right now. Nothing was placed; try again shortly.",
                5, Map.of());
    }
}
