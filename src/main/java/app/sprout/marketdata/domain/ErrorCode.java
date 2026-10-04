package app.sprout.marketdata.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the market-data contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request isn't valid"),
    UNKNOWN_INSTRUMENT(HttpStatus.NOT_FOUND, "No such instrument"),
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
