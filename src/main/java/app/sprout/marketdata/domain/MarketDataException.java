package app.sprout.marketdata.domain;

/** A failure the client should hear about, with its contract code. */
public class MarketDataException extends RuntimeException {

    private final ErrorCode code;
    private final Integer retryAfterSeconds;

    public MarketDataException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    public MarketDataException(ErrorCode code, String detail, Integer retryAfterSeconds) {
        super(detail);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
