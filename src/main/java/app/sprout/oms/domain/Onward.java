package app.sprout.oms.domain;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.http.HttpRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries the request this service is working on to every service it calls: its id (so one request
 * can be followed through every service's logs) and, when tracing is on, its trace (W3C traceparent),
 * so the calls appear as one trace. Work started by a schedule carries that work's trace.
 */
@Component
public class Onward {

    public static final String REQUEST_ID = "X-Request-Id";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public Onward(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Adds the request id and trace headers to an outgoing call, and returns it. */
    public HttpRequest.Builder headers(HttpRequest.Builder req) {
        String id = MDC.get("requestId");
        if (id != null) {
            req.setHeader(REQUEST_ID, id);
        }
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span != null && p != null) {
            p.inject(span.context(), req, HttpRequest.Builder::setHeader);
        }
        return req;
    }
}
