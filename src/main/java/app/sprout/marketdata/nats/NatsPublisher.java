package app.sprout.marketdata.nats;

import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.Model.MarketListener;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Tick;
import app.sprout.marketdata.web.Dtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes every tick as a {@code marketdata.tick} v1 event on {@code md.tick.<SYMBOL>}.
 *
 * <p>NATS is optional for prices to flow: the service starts without it, connects in the background
 * and keeps reconnecting. While disconnected, ticks for NATS are dropped and counted, not buffered.
 * A tick is superseded by the next one within seconds, so delivering a backlog of stale prices after
 * an outage would be worse than skipping them. Clients streaming over HTTP are unaffected.
 */
public final class NatsPublisher implements MarketListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsPublisher.class);

    private final String url;
    private final ObjectMapper json;
    private final Counter published;
    private final Counter dropped;
    private volatile Connection connection;
    private volatile MarketView market;

    public NatsPublisher(String url, MarketEngine engine, ObjectMapper json, MeterRegistry meters) {
        this.url = url;
        this.json = json;
        this.market = engine.market();
        this.published = meters.counter("md.nats.ticks", "result", "published");
        this.dropped = meters.counter("md.nats.ticks", "result", "dropped");
    }

    public void start() throws InterruptedException {
        ConnectionListener listener = (conn, event) -> {
            log.info("NATS {}", event);
            switch (event) {
                case CONNECTED, RECONNECTED -> connection = conn;
                default -> { }
            }
        };
        Options options = new Options.Builder()
                .server(url)
                .connectionName("sprout-marketdata")
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1))
                .connectionTimeout(Duration.ofSeconds(2))
                .reconnectBufferSize(0) // drop, don't queue, while disconnected (see class comment)
                .connectionListener(listener)
                .errorListener(new ErrorListener() { })
                .build();
        Nats.connectAsynchronously(options, true);
    }

    public boolean connected() {
        Connection c = connection;
        return c != null && c.getStatus() == Connection.Status.CONNECTED;
    }

    @Override
    public void onTick(Tick tick) {
        Connection c = connection;
        if (c == null || c.getStatus() != Connection.Status.CONNECTED) {
            dropped.increment();
            return;
        }
        try {
            c.publish("md.tick." + tick.symbol(), json.writeValueAsBytes(event(tick)));
            published.increment();
        } catch (IllegalStateException | JsonProcessingException e) {
            dropped.increment();
        }
    }

    @Override
    public void onMarket(MarketView m) {
        market = m;
    }

    private Map<String, Object> event(Tick t) {
        MarketView m = market;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventType", "marketdata.tick");
        e.put("eventVersion", 1);
        e.put("producer", "sprout-marketdata");
        e.put("mode", m.mode().name());
        e.put("sessionDate", m.sessionDate().toString());
        e.put("symbol", t.symbol());
        e.put("price", Dtos.money(t.price()));
        e.put("size", t.size());
        e.put("ts", Dtos.ist(t.ts()));
        e.put("seq", t.seq());
        e.put("emittedAt", DateTimeFormatter.ISO_INSTANT.format(t.emittedAt()));
        return e;
    }

    @Override
    public void close() throws InterruptedException {
        Connection c = connection;
        if (c != null) {
            c.close();
        }
    }
}
