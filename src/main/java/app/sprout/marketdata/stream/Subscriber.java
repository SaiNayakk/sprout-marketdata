package app.sprout.marketdata.stream;

import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Quote;
import app.sprout.marketdata.domain.Model.Tick;
import app.sprout.marketdata.web.Dtos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One client's price stream. The engine side only ever replaces the latest pending tick per symbol;
 * this subscriber's own thread sends whatever is pending, oldest market state first. Within a
 * symbol, {@code seq} only ever goes up on the wire.
 */
final class Subscriber {

    private final StreamHub hub;
    private final MarketEngine engine;
    private final SseEmitter emitter;
    private final List<String> symbols;
    private final Duration heartbeat;
    private final Map<String, Tick> pending = new ConcurrentHashMap<>();
    private final AtomicReference<MarketView> pendingMarket = new AtomicReference<>();
    private final Semaphore wake = new Semaphore(0);
    private final Map<String, Long> lastSeq = new HashMap<>();
    private final Counter ticksSent;
    private final Counter heartbeats;
    private volatile boolean resync = true; // the first thing a new stream gets is the current state
    private volatile boolean closed;
    private Thread thread;

    Subscriber(StreamHub hub, MarketEngine engine, SseEmitter emitter, List<String> symbols, Duration heartbeat,
               MeterRegistry meters) {
        this.hub = hub;
        this.engine = engine;
        this.emitter = emitter;
        this.symbols = List.copyOf(symbols);
        this.heartbeat = heartbeat;
        this.ticksSent = meters.counter("md.stream.events", "type", "tick");
        this.heartbeats = meters.counter("md.stream.events", "type", "heartbeat");
    }

    List<String> symbols() {
        return symbols;
    }

    void start() {
        signal(); // send the current state straight away, not at the first tick or heartbeat
        thread = Thread.ofVirtual().name("md-stream").start(this::run);
    }

    void offer(Tick tick) {
        if (closed) {
            return;
        }
        if (pending.put(tick.symbol(), tick) != null) {
            hub.conflated();
        }
        signal();
    }

    void offerMarket(MarketView market) {
        pendingMarket.set(market);
        signal();
    }

    void requestResync() {
        resync = true;
        signal();
    }

    private void signal() {
        if (wake.availablePermits() == 0) {
            wake.release();
        }
    }

    private void run() {
        try {
            while (!closed) {
                boolean woken = wake.tryAcquire(heartbeat.toMillis(), TimeUnit.MILLISECONDS);
                wake.drainPermits();
                if (resync) {
                    resync = false;
                    pendingMarket.set(null);
                    send("market", Dtos.market(engine.market()));
                    for (String sym : symbols) {
                        Quote q = engine.quote(sym);
                        send("quote", Dtos.quote(q));
                        lastSeq.put(sym, q.seq());
                    }
                }
                MarketView m = pendingMarket.getAndSet(null);
                if (m != null) {
                    send("market", Dtos.market(m));
                }
                for (String sym : symbols) {
                    Tick t = pending.remove(sym);
                    if (t != null && t.seq() > lastSeq.getOrDefault(sym, 0L)) {
                        send("tick", Dtos.tick(t));
                        lastSeq.put(sym, t.seq());
                        ticksSent.increment();
                    }
                }
                if (!woken) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeats.increment();
                }
            }
        } catch (IOException | IllegalStateException e) {
            // the client went away or the stream was completed; nothing to tell anyone
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    private void send(String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        hub.remove(this);
        signal();
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // already completed or the connection is gone
        }
    }
}
