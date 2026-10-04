package app.sprout.marketdata.nats;

import static org.assertj.core.api.Assertions.assertThat;

import app.sprout.contracts.Contracts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticks reach NATS as marketdata.tick v1 events that match the published schema. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.config.name=marketdata",
        "sprout.marketdata.clock=ACCELERATED",
        "sprout.marketdata.speed=60",
        "sprout.marketdata.start-date=2026-10-05",
        "sprout.marketdata.pre-open=0s"})
class NatsEventsTest {

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withExposedPorts(4222);

    @DynamicPropertySource
    static void nats(DynamicPropertyRegistry r) {
        r.add("sprout.marketdata.nats-url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }

    @Autowired ObjectMapper json;
    @Autowired NatsPublisher publisher;

    @Test
    void ticksArePublishedPerSymbolAndMatchTheContract() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Contracts.read(Contracts.TICK_V1));
        try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
            Subscription sub = nc.subscribe("md.tick.>");
            List<JsonNode> events = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (events.size() < 200 && System.currentTimeMillis() < deadline) {
                Message m = sub.nextMessage(Duration.ofSeconds(1));
                if (m != null) {
                    JsonNode e = json.readTree(m.getData());
                    assertThat(m.getSubject()).isEqualTo("md.tick." + e.path("symbol").asText());
                    events.add(e);
                }
            }
            assertThat(events).hasSizeGreaterThanOrEqualTo(200);
            assertThat(events).allSatisfy(e -> assertThat(schema.validate(e)).isEmpty());
            assertThat(events).extracting(e -> e.path("sessionDate").asText()).containsOnly("2026-10-05");
        }
        assertThat(publisher.connected()).isTrue();
    }
}
