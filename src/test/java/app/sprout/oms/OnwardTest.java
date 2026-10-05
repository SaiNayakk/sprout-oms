package app.sprout.oms;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.oms.domain.Onward;
import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Calls this service makes carry the request they're part of. */
class OnwardTest {

    final StaticListableBeanFactory none = new StaticListableBeanFactory();
    final Onward onward = new Onward(none.getBeanProvider(io.micrometer.tracing.Tracer.class),
            none.getBeanProvider(io.micrometer.tracing.propagation.Propagator.class));

    @AfterEach
    void clear() {
        MDC.remove("requestId");
    }

    @Test
    void theRequestIdGoesOnwardAndNothingIsInventedWithoutOne() {
        MDC.put("requestId", "req-123");
        HttpRequest with = onward.headers(HttpRequest.newBuilder(URI.create("http://127.0.0.1/x"))).build();
        assertThat(with.headers().firstValue(Onward.REQUEST_ID)).contains("req-123");
        MDC.remove("requestId");
        HttpRequest without = onward.headers(HttpRequest.newBuilder(URI.create("http://127.0.0.1/x"))).build();
        assertThat(without.headers().firstValue(Onward.REQUEST_ID)).isEmpty();
        assertThat(without.headers().firstValue("traceparent")).as("no tracer: no trace header").isEmpty();
    }
}
