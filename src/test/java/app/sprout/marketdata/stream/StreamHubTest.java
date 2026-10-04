package app.sprout.marketdata.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.sprout.marketdata.MutableClock;
import app.sprout.marketdata.domain.MarketDataException;
import app.sprout.marketdata.domain.MarketEngine;
import app.sprout.marketdata.domain.MarketEngine.ClockMode;
import app.sprout.marketdata.domain.MarketSimulator;
import app.sprout.marketdata.domain.Model.Tick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** What a slow or vanished client does to the stream: nothing bad. */
class StreamHubTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T04:00:00Z"));
    final MarketEngine engine = new MarketEngine(new MarketSimulator(12, Map.of()),
            new MarketEngine.Settings(ClockMode.ACCELERATED, 60, LocalDate.of(2026, 10, 5), Duration.ZERO,
                    Duration.ofSeconds(20), 20, Duration.ofSeconds(5)), clock);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final StreamHub hub = new StreamHub(engine, 2, 50, Duration.ofSeconds(15), meters);

    /** A client whose connection blocks until released, recording what it was sent. */
    static class SlowClient extends SseEmitter {
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public void send(SseEventBuilder event) throws IOException {
            if (fail) {
                throw new IOException("Broken pipe");
            }
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            StringBuilder text = new StringBuilder();
            event.build().forEach(part -> text.append(part.getData()));
            sent.add(text.toString());
        }
    }

    Tick tick(long seq) {
        return new Tick("HARBOR", 500 + seq * 0.05, 10, Instant.parse("2026-10-05T03:46:00Z").plusMillis(seq), seq,
                clock.instant());
    }

    @Test
    void aSlowClientGetsTheLatestPriceNotABacklog() throws Exception {
        SlowClient client = new SlowClient();
        hub.open(List.of("HARBOR"), client);
        for (long seq = 1; seq <= 5_000; seq++) {
            hub.onTick(tick(seq)); // the engine never waits for this client
        }
        client.release.countDown();
        Thread.sleep(500);
        List<String> ticks = client.sent.stream().filter(s -> s.contains("event:tick")).toList();
        assertThat(ticks).as("at most a couple of ticks, not 5,000").hasSizeLessThanOrEqualTo(2);
        assertThat(ticks.get(ticks.size() - 1)).contains("seq=5000"); // this fake client sees the object, not JSON
        assertThat(meters.counter("md.stream.conflated").count()).isGreaterThanOrEqualTo(4_998);
    }

    @Test
    void aVanishedClientIsForgotten() throws Exception {
        SlowClient client = new SlowClient();
        client.fail = true;
        hub.open(List.of("HARBOR"), client);
        Thread.sleep(300);
        assertThat(meters.get("md.stream.subscribers").gauge().value()).isZero();
        hub.onTick(tick(1)); // nobody left to send to; nothing breaks
    }

    @Test
    void theServiceSaysSoWhenItIsFull() {
        hub.open(List.of("HARBOR"), new SlowClient());
        hub.open(List.of("HARBOR"), new SlowClient());
        assertThatThrownBy(() -> hub.open(List.of("HARBOR"), new SlowClient()))
                .isInstanceOf(MarketDataException.class)
                .satisfies(e -> assertThat(((MarketDataException) e).retryAfterSeconds()).isEqualTo(10));
    }
}
