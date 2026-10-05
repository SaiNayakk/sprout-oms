package app.sprout.oms.domain;

import app.sprout.oms.domain.Upstreams.Leg;
import app.sprout.oms.domain.Upstreams.Reply;
import app.sprout.oms.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Ledger entries the order service has decided on, kept in its own database in the same transaction
 * as the decision, then posted to the ledger in order until it accepts them. A fill or a release is
 * never lost and never booked twice (each entry has a fixed idempotency key).
 *
 * <p>Holds are the one thing posted directly, while the customer waits, because placing an order
 * depends on the answer. A hold whose answer never came is settled by an UNDO_HOLD: post the same
 * hold again (the ledger answers with the original if it was booked, or books it now) and then
 * release it, so either way it ends at zero. If the ledger refuses the hold, it was never booked and
 * there is nothing to undo.
 */
@Component
public class LedgerOutbox {

    private static final Logger log = LoggerFactory.getLogger(LedgerOutbox.class);

    /** Builds a balanced entry, netting each account's debits and credits. Zero legs are dropped. */
    public static final class Entry {
        private final Map<String, Long> net = new TreeMap<>();   // + debit, - credit

        public Entry debit(String account, long paise) {
            net.merge(account, paise, Long::sum);
            return this;
        }

        public Entry credit(String account, long paise) {
            net.merge(account, -paise, Long::sum);
            return this;
        }

        public List<Leg> legs() {
            List<Leg> legs = new ArrayList<>();
            net.forEach((account, n) -> {
                if (n > 0) {
                    legs.add(new Leg(account, "DEBIT", n));
                } else if (n < 0) {
                    legs.add(new Leg(account, "CREDIT", -n));
                }
            });
            long balance = net.values().stream().mapToLong(Long::longValue).sum();
            if (balance != 0) {
                throw new IllegalStateException("unbalanced entry: " + net);
            }
            return legs;
        }
    }

    private record Due(long seq, String key, String kind, String body) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;
    private final ObjectMapper json;
    private final ReentrantLock flushing = new ReentrantLock();

    public LedgerOutbox(JdbcClient db, Clock clock, Upstreams up, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.up = up;
        this.json = json;
    }

    public static String cash(UUID user) {
        return "customer:" + user + ":cash";
    }

    public static String hold(UUID user) {
        return "customer:" + user + ":order-hold";
    }

    public static String unsettled(UUID user) {
        return "customer:" + user + ":unsettled";
    }

    public static String dues(UUID user) {
        return "customer:" + user + ":dues";
    }

    /** The body of a journal entry, as the ledger takes it. */
    String body(String key, String description, String reference, List<Leg> legs) {
        return up.write(Map.of("idempotencyKey", key, "description", description, "reference", reference,
                "postings", legs.stream().map(l -> Map.of("account", l.account(), "side", l.side(), "amount", Money.rupees(l.paise()))).toList()));
    }

    /** Records an entry to post. Call inside the transaction that decided it. Entries with no legs are skipped. */
    public void add(String key, String description, String reference, Entry entry) {
        List<Leg> legs = entry.legs();
        if (legs.isEmpty()) {
            return;
        }
        insert(key, "POST", body(key, description, reference, legs));
    }

    /** Records that a hold of this amount for this order may or may not have been booked, and must end at zero. */
    public void undoHold(UUID order, UUID user, long paise) {
        String hold = body("order-hold:" + order, "Money blocked for an order", order.toString(), holdLegs(user, paise));
        String release = body("release:" + order, "Order didn't go ahead", order.toString(),
                new Entry().debit(hold(user), paise).credit(cash(user), paise).legs());
        insert("undo-hold:" + order, "UNDO_HOLD", up.write(Map.of("hold", hold, "release", release)));
    }

    static List<Leg> holdLegs(UUID user, long paise) {
        return new Entry().debit(cash(user), paise).credit(hold(user), paise).legs();
    }

    private void insert(String key, String kind, String body) {
        db.sql("INSERT INTO ledger_outbox (key, kind, body, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (key) DO NOTHING")
                .params(key, kind, body, Timestamp.from(clock.instant())).update();
    }

    /** Posts what is waiting, oldest first. Safe to call from anywhere; one flush runs at a time. */
    public int flush() {
        if (!flushing.tryLock()) {
            return 0;
        }
        try {
            List<Due> due = db.sql("SELECT seq, key, kind, body FROM ledger_outbox WHERE posted_at IS NULL ORDER BY seq LIMIT 100")
                    .query((rs, n) -> new Due(rs.getLong("seq"), rs.getString("key"), rs.getString("kind"), rs.getString("body")))
                    .list();
            int posted = 0;
            for (Due d : due) {
                String error;
                try {
                    error = d.kind().equals("POST") ? post(d.body()) : undo(d.body());
                } catch (Unreachable e) {
                    return posted;   // the ledger is away: try again later, in order
                }
                if (error == null) {
                    db.sql("UPDATE ledger_outbox SET posted_at = ?, attempts = attempts + 1, last_error = NULL WHERE seq = ?")
                            .params(Timestamp.from(clock.instant()), d.seq()).update();
                    posted++;
                } else {
                    // a definite refusal means a bug in what was decided: keep it, shout, and carry on with the rest
                    db.sql("UPDATE ledger_outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?").params(error, d.seq()).update();
                    log.error("The ledger refused {}: {}", d.key(), error);
                }
            }
            return posted;
        } finally {
            flushing.unlock();
        }
    }

    private String post(String body) {
        Reply r = up.postBody(body);
        return r.ok() ? null : r.status() + " " + r.code();
    }

    private String undo(String body) {
        JsonNode both;
        try {
            both = json.readTree(body);
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
        Reply held = up.postBody(both.path("hold").asText());
        if (held.status() == 422 && held.code().equals("INSUFFICIENT_FUNDS")) {
            return null;   // never booked, so nothing to undo
        }
        if (!held.ok()) {
            return held.status() + " " + held.code();
        }
        return post(both.path("release").asText());
    }
}
