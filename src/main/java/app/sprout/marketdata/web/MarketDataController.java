package app.sprout.marketdata.web;

import app.sprout.marketdata.domain.ErrorCode;
import app.sprout.marketdata.domain.MarketDataException;
import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.Model.Candle;
import app.sprout.marketdata.stream.StreamHub;
import app.sprout.marketdata.web.Dtos.CandlesDto;
import app.sprout.marketdata.web.Dtos.InstrumentDto;
import app.sprout.marketdata.web.Dtos.InstrumentsDto;
import app.sprout.marketdata.web.Dtos.MarketDto;
import app.sprout.marketdata.web.Dtos.QuotesDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The market-data API. See marketdata-v1.yaml in sprout-contracts. */
@RestController
public class MarketDataController {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&_-]{1,20}");
    private static final int MAX_CANDLES = 500;

    private final MarketEngine engine;
    private final StreamHub hub;

    public MarketDataController(MarketEngine engine, StreamHub hub) {
        this.engine = engine;
        this.hub = hub;
    }

    @GetMapping("/v1/market")
    public MarketDto market() {
        return Dtos.market(engine.market());
    }

    @GetMapping("/v1/instruments")
    public InstrumentsDto instruments() {
        return new InstrumentsDto(engine.instruments().stream().map(Dtos::instrument).toList());
    }

    @GetMapping("/v1/instruments/{symbol}")
    public InstrumentDto instrument(@PathVariable String symbol) {
        return Dtos.instrument(engine.instrument(known(symbol)).orElseThrow());
    }

    @GetMapping("/v1/quotes")
    public QuotesDto quotes(@RequestParam String symbols) {
        List<String> list = symbols(symbols);
        return new QuotesDto(Dtos.market(engine.market()), list.stream().map(s -> Dtos.quote(engine.quote(s))).toList());
    }

    @GetMapping("/v1/candles/{symbol}")
    public CandlesDto candles(@PathVariable String symbol, @RequestParam String interval,
                              @RequestParam(required = false) Integer limit) {
        String sym = known(symbol);
        int n = limit == null ? MAX_CANDLES : limit;
        if (n < 1 || n > MAX_CANDLES) {
            throw new MarketDataException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_CANDLES + ".");
        }
        List<Candle> candles = switch (interval) {
            case "1m" -> engine.minuteCandles(sym, n);
            case "1d" -> engine.dailyCandles(sym, n);
            default -> throw new MarketDataException(ErrorCode.VALIDATION_FAILED, "interval must be 1m or 1d.");
        };
        return new CandlesDto(sym, interval, candles.stream().map(Dtos::candle).toList());
    }

    // no "produces": errors before the stream starts must still be able to answer as problem+json
    @GetMapping("/v1/stream")
    public SseEmitter stream(@RequestParam String symbols) {
        return hub.open(symbols(symbols));
    }

    /** Parses "a, B,c" into known, upper-case, distinct symbols in the order given. */
    private List<String> symbols(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String s = part.trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            throw new MarketDataException(ErrorCode.VALIDATION_FAILED, "Give at least one symbol, e.g. symbols=HARBOR,INKWELL.");
        }
        if (out.size() > hub.maxSymbols()) {
            throw new MarketDataException(ErrorCode.VALIDATION_FAILED, "At most " + hub.maxSymbols() + " symbols at a time.");
        }
        List<String> unknown = new ArrayList<>();
        for (String s : out) {
            if (!SYMBOL.matcher(s).matches() || engine.instrument(s).isEmpty()) {
                unknown.add(s);
            }
        }
        if (!unknown.isEmpty()) {
            throw new MarketDataException(ErrorCode.UNKNOWN_INSTRUMENT, "No instrument called " + String.join(", ", unknown) + ".");
        }
        return List.copyOf(out);
    }

    private String known(String symbol) {
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(s).matches() || engine.instrument(s).isEmpty()) {
            throw new MarketDataException(ErrorCode.UNKNOWN_INSTRUMENT, "No instrument called " + s + ".");
        }
        return s;
    }
}
