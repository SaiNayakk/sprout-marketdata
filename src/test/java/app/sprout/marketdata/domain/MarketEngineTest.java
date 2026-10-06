package app.sprout.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.marketdata.MutableClock;
import app.sprout.marketdata.domain.MarketEngine.ClockMode;
import app.sprout.marketdata.domain.Model.Candle;
import app.sprout.marketdata.domain.Model.MarketListener;
import app.sprout.marketdata.domain.Model.MarketState;
import app.sprout.marketdata.domain.Model.MarketView;
import app.sprout.marketdata.domain.Model.Quote;
import app.sprout.marketdata.domain.Model.Tick;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The market clock, sessions, and the rule that the future is never revealed. */
class MarketEngineTest {

    static final LocalDate FRI = LocalDate.of(2026, 10, 2);
    static final LocalDate MON = LocalDate.of(2026, 10, 5);
    final MarketSimulator sim = new MarketSimulator(12, Map.of());

    static class Recorder implements MarketListener {
        final List<Tick> ticks = new ArrayList<>();
        final List<MarketView> markets = new ArrayList<>();
        int resyncs;

        @Override public void onTick(Tick t) { ticks.add(t); }
        @Override public void onMarket(MarketView m) { markets.add(m); }
        @Override public void onResync() { resyncs++; }
    }

    MarketEngine accelerated(MutableClock clock, double speed, LocalDate start) {
        return new MarketEngine(sim, new MarketEngine.Settings(ClockMode.ACCELERATED, speed, start, Duration.ofMinutes(1),
                Duration.ofSeconds(20), 20, Duration.ofSeconds(5)), clock);
    }

    /** Drives the engine in small real-time steps, as its thread would. */
    static void run(MarketEngine engine, MutableClock clock, Duration total) {
        Instant end = clock.instant().plus(total);
        while (clock.instant().isBefore(end)) {
            clock.advance(Duration.ofMillis(200));
            engine.advance(clock.instant());
        }
    }

    @Test
    void startsPreOpenThenOpensAt0915() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T20:00:00Z"));
        MarketEngine engine = accelerated(clock, 60, MON);
        assertThat(engine.market().state()).isEqualTo(MarketState.PRE_OPEN);
        assertThat(engine.market().sessionDate()).isEqualTo(MON);
        run(engine, clock, Duration.ofSeconds(2)); // 2 market minutes: past 09:15
        assertThat(engine.market().state()).isEqualTo(MarketState.OPEN);
        assertThat(engine.market().marketTime()).isAfter(Model.at(MON, Model.OPEN));
    }

    @Test
    void neverRevealsTheFuture() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T20:00:00Z"));
        MarketEngine engine = accelerated(clock, 60, MON);
        Recorder rec = new Recorder();
        engine.addListener(rec);
        for (int step = 0; step < 40; step++) {
            run(engine, clock, Duration.ofSeconds(3));
            Instant now = engine.market().marketTime();
            List<Candle> candles = engine.minuteCandles("HARBOR", 500);
            for (Candle c : candles) {
                if (c.complete()) {
                    assertThat(c.start().plusSeconds(60)).as("complete candle").isBeforeOrEqualTo(now);
                } else {
                    assertThat(c.start()).isBeforeOrEqualTo(now);
                    assertThat(c.start().plusSeconds(60)).isAfter(now);
                }
            }
            assertThat(candles.stream().filter(c -> !c.complete()).count()).isLessThanOrEqualTo(1);
            assertThat(rec.ticks).allSatisfy(t -> assertThat(t.ts()).isBeforeOrEqualTo(now));
        }
    }

    @Test
    void ticksAreInOrderAndSeqGoesUpByOne() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T20:00:00Z"));
        MarketEngine engine = accelerated(clock, 60, MON);
        Recorder rec = new Recorder();
        engine.addListener(rec);
        run(engine, clock, Duration.ofSeconds(30));
        assertThat(rec.ticks).hasSizeGreaterThan(1000);
        assertThat(rec.ticks).extracting(Tick::ts).isSorted();
        Map<String, Long> last = new HashMap<>();
        for (Tick t : rec.ticks) {
            long prev = last.getOrDefault(t.symbol(), 0L);
            assertThat(t.seq()).as(t.symbol()).isEqualTo(prev + 1);
            last.put(t.symbol(), t.seq());
        }
        Tick latest = rec.ticks.stream().filter(t -> t.symbol().equals("INKWELL")).reduce((a, b) -> b).orElseThrow();
        Quote q = engine.quote("INKWELL");
        assertThat(q.last()).isEqualTo(latest.price());
        assertThat(q.seq()).isEqualTo(latest.seq());
        assertThat(q.high()).isGreaterThanOrEqualTo(q.last());
        assertThat(q.low()).isLessThanOrEqualTo(q.last());
    }

    @Test
    void aFullSessionClosesWithEveryCandleThenMovesToTheNextTradingDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T20:00:00Z"));
        MarketEngine engine = accelerated(clock, 600, FRI); // 6.25 market hours in about 38 s
        Recorder rec = new Recorder();
        engine.addListener(rec);
        run(engine, clock, Duration.ofSeconds(45));
        assertThat(engine.market().state()).isEqualTo(MarketState.CLOSED);
        List<Candle> day = engine.minuteCandles("HARBOR", 500);
        assertThat(day).hasSize(Model.MINUTES_PER_SESSION).allMatch(Candle::complete);
        assertThat(day).extracting(Candle::close).last().isEqualTo(engine.quote("HARBOR").last());
        double fridayClose = engine.quote("HARBOR").last();
        Candle today = engine.dailyCandles("HARBOR", 500).get(engine.dailyCandles("HARBOR", 500).size() - 1);
        assertThat(today.complete()).isTrue();

        run(engine, clock, Duration.ofSeconds(25)); // the closed pause is 20 s
        assertThat(engine.market().sessionDate()).isEqualTo(MON);
        assertThat(engine.market().state()).isIn(MarketState.PRE_OPEN, MarketState.OPEN);
        assertThat(engine.quote("HARBOR").prevClose()).isEqualTo(fridayClose);
        assertThat(rec.markets).extracting(MarketView::state)
                .containsSubsequence(MarketState.OPEN, MarketState.CLOSED, MarketState.PRE_OPEN);
        assertThat(rec.resyncs).isZero();
    }

    @Test
    void afterASleepItCatchesUpSilentlyAndAsksClientsToResync() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-04T20:00:00Z"));
        MarketEngine engine = accelerated(clock, 60, MON);
        Recorder rec = new Recorder();
        engine.addListener(rec);
        run(engine, clock, Duration.ofSeconds(5));
        int before = rec.ticks.size();
        clock.advance(Duration.ofMinutes(2)); // the laptop slept: two real minutes = two market hours
        engine.advance(clock.instant());
        assertThat(rec.ticks).as("no flood of stale ticks").hasSize(before);
        assertThat(rec.resyncs).isEqualTo(1);
        assertThat(engine.minuteCandles("HARBOR", 500).size()).isGreaterThan(120);
        run(engine, clock, Duration.ofSeconds(2));
        assertThat(rec.ticks).as("live again").hasSizeGreaterThan(before);
    }

    @Test
    void aRestartedMarketResumesWhereTheRunningOneIsInsteadOfStartingOver() {
        Instant epoch = Instant.parse("2026-10-01T20:00:00Z");
        MarketEngine.Settings settings = new MarketEngine.Settings(ClockMode.ACCELERATED, 600, FRI, Duration.ofMinutes(1),
                Duration.ofSeconds(20), 20, Duration.ofSeconds(5), epoch);
        MutableClock running = new MutableClock(epoch);
        MarketEngine engine = new MarketEngine(sim, settings, running);
        run(engine, running, Duration.ofSeconds(150));   // sessions last 57.6 s here: Fri, Mon, and a third of the way into Tue
        assertThat(engine.market().sessionDate()).isEqualTo(LocalDate.of(2026, 10, 6));

        MutableClock after = new MutableClock(running.instant());   // the process restarted: a new engine, the same moment
        MarketEngine restarted = new MarketEngine(sim, settings, after);
        restarted.advance(after.instant());
        assertThat(restarted.market().sessionDate()).isEqualTo(engine.market().sessionDate());
        assertThat(restarted.market().state()).isEqualTo(engine.market().state());
        assertThat(Duration.between(restarted.market().marketTime(), engine.market().marketTime()).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(restarted.quote("HARBOR").last()).as("the same price, not a replay of an old day").isEqualTo(engine.quote("HARBOR").last());
        assertThat(restarted.quote("HARBOR").prevClose()).isEqualTo(engine.quote("HARBOR").prevClose());
    }

    @Test
    void beforeItsEpochAMarketStartsAtItsFirstSession() {
        Instant epoch = Instant.parse("2026-10-02T00:00:00Z");
        MutableClock clock = new MutableClock(epoch.minusSeconds(30));
        MarketEngine engine = new MarketEngine(sim, new MarketEngine.Settings(ClockMode.ACCELERATED, 600, FRI, Duration.ofMinutes(1),
                Duration.ofSeconds(20), 20, Duration.ofSeconds(5), epoch), clock);
        assertThat(engine.market().sessionDate()).isEqualTo(FRI);
        assertThat(engine.market().state()).isEqualTo(MarketState.PRE_OPEN);
    }

    @Test
    void anEpochWithoutAStartDateIsRefusedBecauseTheMarketCouldNotBePlacedAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MarketEngine(sim, new MarketEngine.Settings(ClockMode.ACCELERATED, 600,
                null, Duration.ofMinutes(1), Duration.ofSeconds(20), 20, Duration.ofSeconds(5), Instant.parse("2026-10-01T00:00:00Z")), clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("start date");
    }

    @Test
    void wallClockFollowsRealIndianTime() {
        Instant monNoon = Model.at(MON, LocalTime.of(12, 0, 30));
        MutableClock clock = new MutableClock(monNoon);
        MarketEngine engine = new MarketEngine(sim, new MarketEngine.Settings(ClockMode.WALL, 1, null,
                Duration.ofMinutes(1), Duration.ofSeconds(20), 20, Duration.ofSeconds(5)), clock);
        engine.advance(clock.instant());
        assertThat(engine.market().state()).isEqualTo(MarketState.OPEN);
        assertThat(engine.market().sessionDate()).isEqualTo(MON);
        assertThat(engine.market().speed()).isEqualTo(1.0);
        assertThat(engine.minuteCandles("HARBOR", 500)).hasSize(165 + 1); // 09:15-12:00 done, 12:00 forming

        MutableClock saturday = new MutableClock(Model.at(LocalDate.of(2026, 10, 3), LocalTime.NOON));
        MarketEngine weekend = new MarketEngine(sim, new MarketEngine.Settings(ClockMode.WALL, 1, null,
                Duration.ofMinutes(1), Duration.ofSeconds(20), 20, Duration.ofSeconds(5)), saturday);
        weekend.advance(saturday.instant());
        assertThat(weekend.market().state()).isEqualTo(MarketState.CLOSED);
        assertThat(weekend.market().sessionDate()).isEqualTo(FRI);
    }
}
