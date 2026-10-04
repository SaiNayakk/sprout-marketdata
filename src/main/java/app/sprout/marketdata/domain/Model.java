package app.sprout.marketdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** The small value types the market-data service is built from. */
public final class Model {

    private Model() {}

    /** Indian Standard Time: market hours, sessions and every timestamp clients see. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalTime OPEN = LocalTime.of(9, 15);
    public static final LocalTime CLOSE = LocalTime.of(15, 30);
    public static final int MINUTES_PER_SESSION = 375;

    public enum Mode { REPLAY, SYNTHETIC, LIVE }

    public enum MarketState { PRE_OPEN, OPEN, CLOSED }

    public enum InstrumentType { EQUITY, INDEX }

    public record Instrument(String symbol, String name, String industry, InstrumentType type, double tickSize) {
        public boolean tradable() {
            return type == InstrumentType.EQUITY;
        }
    }

    /** One candle's worth of trading: a minute or a day starting at {@code start}. */
    public record Bar(Instant start, double open, double high, double low, double close, long volume) {}

    /** One price change. {@code ts} is market time; {@code emittedAt} is when the service produced it. */
    public record Tick(String symbol, double price, long size, Instant ts, long seq, Instant emittedAt) {}

    public record Quote(String symbol, double last, double open, double high, double low, double prevClose,
                        long volume, Instant ts, long seq) {}

    public record Candle(Instant start, double open, double high, double low, double close, long volume,
                         boolean complete) {}

    public record MarketView(Mode mode, MarketState state, LocalDate sessionDate, Instant marketTime, double speed,
                             String source) {}

    /** Told about every tick and every change of market state, on the engine's thread. Must not block. */
    public interface MarketListener {
        void onTick(Tick tick);

        void onMarket(MarketView market);

        /** The engine skipped ahead without publishing ticks (it fell behind); listeners should resync. */
        default void onResync() {}
    }

    public static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(IST).toInstant();
    }
}
