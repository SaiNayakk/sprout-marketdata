package app.sprout.marketdata.stream;

import app.sprout.marketdata.domain.ErrorCode;
import app.sprout.marketdata.domain.MarketDataException;
import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.Model.MarketListener;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Tick;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans ticks out to every open price stream.
 *
 * <p>The engine calls {@link #onTick} on its own thread, so this must never block: it only records
 * the tick as the latest for that symbol on each interested {@link Subscriber} and wakes it. Each
 * subscriber has its own virtual thread that writes to its client. A slow client therefore gets the
 * newest price per symbol (conflation) and can never slow the market down or grow memory: at most
 * one pending tick per subscribed symbol.
 */
public final class StreamHub implements MarketListener {

    private final MarketEngine engine;
    private final int maxStreams;
    private final int maxSymbols;
    private final Duration heartbeat;
    private final Set<Subscriber> all = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<Subscriber>> bySymbol = new ConcurrentHashMap<>();
    private final Counter conflated;
    private final MeterRegistry meters;

    public StreamHub(MarketEngine engine, int maxStreams, int maxSymbols, Duration heartbeat, MeterRegistry meters) {
        this.engine = engine;
        this.maxStreams = maxStreams;
        this.maxSymbols = maxSymbols;
        this.heartbeat = heartbeat;
        this.meters = meters;
        this.conflated = meters.counter("md.stream.conflated");
        meters.gauge("md.stream.subscribers", all, Set::size);
    }

    public int maxSymbols() {
        return maxSymbols;
    }

    /** Opens a stream for already-validated symbols. */
    public SseEmitter open(List<String> symbols) {
        // no timeout: heartbeats keep it alive, a failed write ends it
        return open(symbols, new SseEmitter(0L));
    }

    SseEmitter open(List<String> symbols, SseEmitter emitter) {
        if (all.size() >= maxStreams) {
            throw new MarketDataException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Too many people are watching prices right now. Try again shortly.", 10);
        }
        Subscriber s = new Subscriber(this, engine, emitter, symbols, heartbeat, meters);
        all.add(s);
        for (String sym : symbols) {
            bySymbol.computeIfAbsent(sym, k -> ConcurrentHashMap.newKeySet()).add(s);
        }
        emitter.onCompletion(s::close);
        emitter.onTimeout(s::close);
        emitter.onError(e -> s.close());
        s.start();
        return emitter;
    }

    void remove(Subscriber s) {
        all.remove(s);
        for (String sym : s.symbols()) {
            Set<Subscriber> set = bySymbol.get(sym);
            if (set != null) {
                set.remove(s);
            }
        }
    }

    void conflated() {
        conflated.increment();
    }

    @Override
    public void onTick(Tick tick) {
        Set<Subscriber> subs = bySymbol.get(tick.symbol());
        if (subs != null) {
            for (Subscriber s : subs) {
                s.offer(tick);
            }
        }
    }

    @Override
    public void onMarket(MarketView market) {
        all.forEach(s -> s.offerMarket(market));
    }

    @Override
    public void onResync() {
        all.forEach(Subscriber::requestResync);
    }

    /** Ends every stream, e.g. on shutdown, so clients reconnect elsewhere. */
    public void closeAll() {
        List.copyOf(all).forEach(Subscriber::close);
    }
}
