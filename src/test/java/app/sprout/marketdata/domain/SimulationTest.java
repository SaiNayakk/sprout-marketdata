package app.sprout.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.sprout.marketdata.domain.MarketSimulator.Scenario;
import app.sprout.marketdata.domain.Model.Bar;
import app.sprout.marketdata.domain.TickSynthesizer.SynthTick;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The simulated market is believable, consistent with itself, and the same every time. */
class SimulationTest {

    static final LocalDate MON = LocalDate.of(2026, 10, 5);
    static final LocalDate FRI = LocalDate.of(2026, 10, 2);
    final MarketSimulator sim = new MarketSimulator(12, Map.of());

    @Test
    void sameSeedAndDayGiveTheSamePrices() {
        assertThat(new MarketSimulator(12, Map.of()).minuteBars(MON)).isEqualTo(sim.minuteBars(MON));
        assertThat(new MarketSimulator(13, Map.of()).minuteBars(MON)).isNotEqualTo(sim.minuteBars(MON));
    }

    @Test
    void everyMinuteBarIsWellFormedAndOnTheTickGrid() {
        sim.minuteBars(MON).forEach((symbol, bars) -> {
            assertThat(bars).as(symbol).hasSize(Model.MINUTES_PER_SESSION);
            double tick = symbol.equals(MarketSimulator.INDEX) ? 0.01 : 0.05;
            for (Bar b : bars) {
                assertThat(b.high()).as(symbol).isGreaterThanOrEqualTo(Math.max(b.open(), b.close()));
                assertThat(b.low()).as(symbol).isLessThanOrEqualTo(Math.min(b.open(), b.close())).isPositive();
                for (double p : new double[] {b.open(), b.high(), b.low(), b.close()}) {
                    assertThat(Math.abs(p / tick - Math.round(p / tick))).as(symbol + " " + p).isLessThan(1e-6);
                }
            }
        });
    }

    @Test
    void eachDayFollowsOnFromThePreviousClose() {
        Map<String, List<Bar>> friday = sim.minuteBars(FRI);
        Map<String, Double> prev = sim.previousCloses(MON);
        friday.forEach((symbol, bars) -> assertThat(prev.get(symbol)).as(symbol).isEqualTo(bars.get(bars.size() - 1).close()));
    }

    @Test
    void dailyHistoryIsExactlyTheMinutesAddedUp() {
        List<Bar> minutes = sim.minuteBars(FRI).get("HARBOR");
        Bar day = sim.dailyBefore("HARBOR", MON, 1).get(0);
        assertThat(day.open()).isEqualTo(minutes.get(0).open());
        assertThat(day.close()).isEqualTo(minutes.get(minutes.size() - 1).close());
        assertThat(day.high()).isEqualTo(minutes.stream().mapToDouble(Bar::high).max().orElseThrow());
        assertThat(day.low()).isEqualTo(minutes.stream().mapToDouble(Bar::low).min().orElseThrow());
        assertThat(day.volume()).isEqualTo(minutes.stream().mapToLong(Bar::volume).sum());
    }

    @Test
    void historyNeverIncludesTheDayAskedAbout() {
        List<Bar> before = sim.dailyBefore("HARBOR", MON, 500);
        assertThat(before.get(before.size() - 1).start().atZone(Model.IST).toLocalDate()).isEqualTo(FRI);
        assertThat(before).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void weekendsAreClosed() {
        assertThat(MarketSimulator.nextTradingDay(FRI)).isEqualTo(MON);
        assertThatThrownBy(() -> sim.minuteBars(LocalDate.of(2026, 10, 3))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrashDayTakesNearlyEverythingDownTogether() {
        MarketSimulator crash = new MarketSimulator(12, Map.of(MON, Scenario.CRASH));
        Map<String, List<Bar>> day = crash.minuteBars(MON);
        Map<String, Double> prev = crash.previousCloses(MON);
        long down = day.entrySet().stream().filter(e -> !e.getKey().equals(MarketSimulator.INDEX))
                .filter(e -> e.getValue().get(e.getValue().size() - 1).close() < prev.get(e.getKey())).count();
        List<Bar> index = day.get(MarketSimulator.INDEX);
        double indexMove = index.get(index.size() - 1).close() / prev.get(MarketSimulator.INDEX) - 1;
        assertThat(down).as("stocks down on a crash day").isGreaterThanOrEqualTo(17);
        assertThat(indexMove).as("index move").isLessThan(-0.03);
        // and the days before a forced scenario are untouched
        assertThat(crash.minuteBars(FRI)).isEqualTo(sim.minuteBars(FRI));
    }

    @Test
    void ticksAddUpToTheirMinuteExactly() {
        for (Bar bar : sim.minuteBars(MON).get("KOSHA")) {
            List<SynthTick> ticks = TickSynthesizer.ticks("KOSHA", bar, 0.05, 20);
            assertThat(ticks.get(0).price()).isEqualTo(bar.open());
            assertThat(ticks.get(ticks.size() - 1).price()).isEqualTo(bar.close());
            assertThat(ticks).extracting(SynthTick::price).contains(bar.high(), bar.low())
                    .allSatisfy(p -> assertThat(p).isBetween(bar.low(), bar.high()));
            assertThat(ticks.stream().mapToLong(SynthTick::size).sum()).isEqualTo(bar.volume());
            assertThat(ticks).extracting(SynthTick::offsetMillis).isSorted()
                    .allSatisfy(ms -> assertThat(ms).isBetween(0, 59_999)).doesNotHaveDuplicates();
            assertThat(TickSynthesizer.ticks("KOSHA", bar, 0.05, 20)).isEqualTo(ticks);
        }
    }

    @Test
    void aFlatMinuteStillWorks() {
        Bar flat = new Bar(Model.at(MON, Model.OPEN), 100, 100, 100, 100, 0);
        assertThat(TickSynthesizer.ticks("X", flat, 0.05, 4)).extracting(SynthTick::price).containsOnly(100.0);
    }
}
