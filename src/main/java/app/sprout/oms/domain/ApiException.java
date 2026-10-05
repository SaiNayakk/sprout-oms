package app.sprout.oms.domain;

import java.util.Map;

/** A failure the caller should hear about, with its contract code and optional extra fields. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Integer retryAfterSeconds;
    private final Map<String, Object> extra;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Integer retryAfterSeconds, Map<String, Object> extra) {
        super(detail);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.extra = extra;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
