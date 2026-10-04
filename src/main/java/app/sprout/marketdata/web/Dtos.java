package app.sprout.marketdata.web;

import app.sprout.marketdata.domain.Model;
import app.sprout.marketdata.domain.Model.Candle;
import app.sprout.marketdata.domain.Model.Instrument;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Quote;
import app.sprout.marketdata.domain.Model.Tick;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The JSON shapes of the market-data contract. Times are written here, as strings, so their format
 * never depends on JSON-library settings: market times in IST with their offset, wall-clock times in UTC.
 */
public final class Dtos {

    private Dtos() {}

    public record MarketDto(String mode, String state, String sessionDate, String marketTime, double speed,
                            String source) {}

    public record InstrumentDto(String symbol, String name, String industry, String type, double tickSize,
                                boolean tradable) {}

    public record QuoteDto(String symbol, double last, double open, double high, double low, double prevClose,
                           double change, double changePercent, long volume, String ts, long seq) {}

    public record QuotesDto(MarketDto market, List<QuoteDto> quotes) {}

    public record CandleDto(String ts, double open, double high, double low, double close, long volume,
                            boolean complete) {}

    public record CandlesDto(String symbol, String interval, List<CandleDto> candles) {}

    public record TickDto(String symbol, double price, long size, String ts, long seq, String emittedAt) {}

    public record InstrumentsDto(List<InstrumentDto> instruments) {}

    public static MarketDto market(MarketView m) {
        return new MarketDto(m.mode().name(), m.state().name(), m.sessionDate().toString(), ist(m.marketTime()),
                m.speed(), m.source());
    }

    public static InstrumentDto instrument(Instrument i) {
        return new InstrumentDto(i.symbol(), i.name(), i.industry(), i.type().name(), i.tickSize(), i.tradable());
    }

    public static QuoteDto quote(Quote q) {
        double change = q.last() - q.prevClose();
        double pct = q.prevClose() == 0 ? 0 : change / q.prevClose() * 100;
        return new QuoteDto(q.symbol(), money(q.last()), money(q.open()), money(q.high()), money(q.low()),
                money(q.prevClose()), money(change), money(pct), q.volume(), ist(q.ts()), q.seq());
    }

    public static CandleDto candle(Candle c) {
        return new CandleDto(ist(c.start()), money(c.open()), money(c.high()), money(c.low()), money(c.close()),
                c.volume(), c.complete());
    }

    public static TickDto tick(Tick t) {
        return new TickDto(t.symbol(), money(t.price()), t.size(), ist(t.ts()), t.seq(),
                DateTimeFormatter.ISO_INSTANT.format(t.emittedAt()));
    }

    public static String ist(Instant t) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atZone(Model.IST).toOffsetDateTime());
    }

    /** Two decimals, without floating-point dust like 0.30000000000000004. */
    public static double money(double x) {
        return Math.round(x * 100) / 100.0;
    }
}
