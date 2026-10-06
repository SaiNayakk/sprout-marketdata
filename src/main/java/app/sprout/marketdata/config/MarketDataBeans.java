package app.sprout.marketdata.config;

import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.MarketSimulator;
import app.sprout.marketdata.nats.NatsPublisher;
import app.sprout.marketdata.stream.StreamHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketDataBeans {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBeans.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MarketSimulator simulator(MarketDataProperties p) {
        return new MarketSimulator(p.seed(), p.scenarios() == null ? Map.of() : p.scenarios());
    }

    @Bean
    MarketEngine engine(MarketSimulator sim, MarketDataProperties p, Clock clock, MeterRegistry meters) {
        long started = System.nanoTime();
        MarketEngine engine = new MarketEngine(sim, new MarketEngine.Settings(p.clock(), p.speed(), p.startDate(),
                p.preOpen(), p.closedPause(), p.ticksPerMinute(), p.catchUpAfter(), p.epoch()), clock);
        log.info("Market ready in {} ms: {}", (System.nanoTime() - started) / 1_000_000, engine.market());
        meters.gauge("md.engine.lag.ms", engine, e -> e.lag().toMillis());
        return engine;
    }

    @Bean
    StreamHub streamHub(MarketEngine engine, MarketDataProperties p, MeterRegistry meters) {
        StreamHub hub = new StreamHub(engine, p.stream().maxStreams(), p.stream().maxSymbols(), p.stream().heartbeat(), meters);
        engine.addListener(hub);
        return hub;
    }

    @Bean(destroyMethod = "close")
    NatsPublisher natsPublisher(MarketDataProperties p, MarketEngine engine, ObjectMapper json, MeterRegistry meters)
            throws InterruptedException {
        if (p.natsUrl() == null || p.natsUrl().isBlank()) {
            return null;
        }
        NatsPublisher nats = new NatsPublisher(p.natsUrl(), engine, json, meters);
        engine.addListener(nats);
        nats.start();
        return nats;
    }

    /** Drives the market clock on one dedicated thread for the life of the service. */
    @Bean
    SmartLifecycle engineRunner(MarketEngine engine, StreamHub hub, Clock clock) {
        return new SmartLifecycle() {
            private volatile Thread thread;

            @Override
            public void start() {
                thread = Thread.ofPlatform().daemon().name("md-engine").start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Instant next = engine.advance(clock.instant());
                            long wait = Duration.between(clock.instant(), next).toMillis();
                            Thread.sleep(Math.max(1, Math.min(250, wait)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException e) {
                            log.error("Market engine step failed; retrying in 1 s", e);
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                });
            }

            @Override
            public void stop() {
                hub.closeAll();
                if (thread != null) {
                    thread.interrupt();
                }
            }

            @Override
            public boolean isRunning() {
                return thread != null && thread.isAlive();
            }
        };
    }

    /** DOWN if the market clock has stalled: the engine thread is stuck or starved. */
    @Bean
    HealthIndicator market(MarketEngine engine) {
        return () -> {
            Duration lag = engine.lag();
            Health.Builder b = lag.compareTo(Duration.ofSeconds(30)) > 0 ? Health.down() : Health.up();
            var m = engine.market();
            return b.withDetail("state", m.state()).withDetail("sessionDate", m.sessionDate())
                    .withDetail("lagMs", lag.toMillis()).build();
        };
    }

    /** Always UP: prices keep flowing to clients without NATS. Shows whether events are being published. */
    @Bean
    HealthIndicator nats(ObjectProvider<NatsPublisher> nats) {
        return () -> {
            NatsPublisher n = nats.getIfAvailable();
            return Health.up().withDetail("enabled", n != null).withDetail("connected", n != null && n.connected()).build();
        };
    }
}
