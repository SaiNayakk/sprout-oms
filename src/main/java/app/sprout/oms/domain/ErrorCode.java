package app.sprout.oms.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the oms contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in first"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No Sprout account"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such order"),
    UNKNOWN_INSTRUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown instrument"),
    ORDER_NOT_OPEN(HttpStatus.CONFLICT, "Order not open"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Invalid signature"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
