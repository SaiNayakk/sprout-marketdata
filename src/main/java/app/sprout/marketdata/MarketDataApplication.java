package app.sprout.marketdata;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The market-data service: instruments, quotes, candles and the live price stream.
 *
 * <p>Runs on its own ({@link #main}) or inside a shared JVM host, which calls {@link #builder()}.
 * Either way it reads {@code marketdata.yml}, never {@code application.yml}, so services sharing a
 * host can't read each other's settings. It has no database: the simulated market is regenerated
 * deterministically from its seed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketDataApplication {

    public static final String CONFIG_NAME = "marketdata";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(MarketDataApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME);
    }
}
