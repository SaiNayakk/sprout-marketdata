package app.sprout.marketdata.config;

import app.sprout.marketdata.domain.MarketEngine.ClockMode;
import app.sprout.marketdata.domain.MarketSimulator.Scenario;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.marketdata} in marketdata.yml. */
@ConfigurationProperties("sprout.marketdata")
public record MarketDataProperties(
        /* WALL: the market follows real Indian time. ACCELERATED: it runs at {@code speed} from {@code startDate}. */
        ClockMode clock,
        double speed,
        LocalDate startDate,
        Duration preOpen,
        Duration closedPause,
        int ticksPerMinute,
        Duration catchUpAfter,
        long seed,
        /* Force a scenario on given days, e.g. a crash for a chaos test or a demo. */
        Map<LocalDate, Scenario> scenarios,
        Stream stream,
        String natsUrl) {

    public record Stream(int maxStreams, int maxSymbols, Duration heartbeat) {}
}
