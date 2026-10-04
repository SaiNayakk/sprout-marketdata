package app.sprout.marketdata.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NATS is down (nothing listens on its address): the service still starts, is healthy, serves
 * prices, and counts the events it couldn't publish instead of queueing them.
 */
@SpringBootTest(properties = {
        "spring.config.name=marketdata",
        "sprout.marketdata.clock=ACCELERATED",
        "sprout.marketdata.speed=60",
        "sprout.marketdata.start-date=2026-10-05",
        "sprout.marketdata.pre-open=0s",
        "sprout.marketdata.nats-url=nats://127.0.0.1:1"})
@AutoConfigureMockMvc
class WithoutNatsTest {

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meters;

    @Test
    void pricesFlowAndHealthStaysUpWithoutNats() throws Exception {
        Thread.sleep(2000);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.nats.details.connected").value(false));
        mvc.perform(get("/v1/quotes").param("symbols", "HARBOR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.quotes[0].seq").value(org.hamcrest.Matchers.greaterThan(0)));
        assertThat(meters.counter("md.nats.ticks", "result", "dropped").count()).isPositive();
        assertThat(meters.counter("md.nats.ticks", "result", "published").count()).isZero();
    }
}
