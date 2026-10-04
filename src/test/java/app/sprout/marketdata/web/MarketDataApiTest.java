package app.sprout.marketdata.web;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The market-data contract inside the service: every JSON response is checked against
 * marketdata-v1.yaml from sprout-contracts, and the price stream is read over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=marketdata",
        "sprout.marketdata.clock=ACCELERATED",
        "sprout.marketdata.speed=60",
        "sprout.marketdata.start-date=2026-10-05",
        "sprout.marketdata.pre-open=0s"})
@AutoConfigureMockMvc
class MarketDataApiTest {

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.MARKETDATA_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .build();

    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void theMarketSaysWhatItIs() throws Exception {
        mvc.perform(get("/v1/market")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.mode").value("SYNTHETIC"))
                .andExpect(jsonPath("$.speed").value(60.0))
                .andExpect(jsonPath("$.sessionDate").value("2026-10-05"))
                .andExpect(jsonPath("$.marketTime").value(org.hamcrest.Matchers.endsWith("+05:30")));
    }

    @Test
    void instrumentsListAndLookup() throws Exception {
        JsonNode all = body(mvc.perform(get("/v1/instruments")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(all.path("instruments")).hasSize(21);
        mvc.perform(get("/v1/instruments/inkwell")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.symbol").value("INKWELL"))
                .andExpect(jsonPath("$.tradable").value(true));
        mvc.perform(get("/v1/instruments/SPROUT20")).andExpect(jsonPath("$.type").value("INDEX"))
                .andExpect(jsonPath("$.tradable").value(false));
        mvc.perform(get("/v1/instruments/NOPE")).andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
    }

    @Test
    void quotesComeInTheOrderAsked() throws Exception {
        JsonNode r = body(mvc.perform(get("/v1/quotes").param("symbols", "harbor, INKWELL,HARBOR"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(r.path("quotes")).extracting(q -> q.path("symbol").asText()).containsExactly("HARBOR", "INKWELL");
        JsonNode q = r.path("quotes").get(0);
        assertThat(q.path("change").asDouble())
                .isCloseTo(q.path("last").asDouble() - q.path("prevClose").asDouble(), org.assertj.core.data.Offset.offset(0.011));
    }

    @Test
    void quoteRequestsAreValidated() throws Exception {
        mvc.perform(get("/v1/quotes")).andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/v1/quotes").param("symbols", " , ")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/quotes").param("symbols", "HARBOR,NOPE,ALSO_NOPE")).andExpect(status().isNotFound())
                .andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("NOPE, ALSO_NOPE")));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            many.append("S").append(i).append(',');
        }
        mvc.perform(get("/v1/quotes").param("symbols", many.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("At most 50")));
    }

    @Test
    void candles() throws Exception {
        Thread.sleep(3000); // a few market minutes
        JsonNode minute = body(mvc.perform(get("/v1/candles/HARBOR").param("interval", "1m"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(minute.path("candles").size()).isGreaterThan(1);
        JsonNode daily = body(mvc.perform(get("/v1/candles/HARBOR").param("interval", "1d").param("limit", "30"))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT));
        assertThat(daily.path("candles")).hasSize(30);
        JsonNode today = daily.path("candles").get(29);
        assertThat(today.path("ts").asText()).startsWith("2026-10-05");
        assertThat(today.path("complete").asBoolean()).isFalse();

        mvc.perform(get("/v1/candles/HARBOR").param("interval", "5m")).andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
        mvc.perform(get("/v1/candles/HARBOR").param("interval", "1d").param("limit", "501")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/candles/HARBOR").param("interval", "1d").param("limit", "many")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/v1/candles/NOPE").param("interval", "1m")).andExpect(status().isNotFound());
    }

    @Test
    void requestIdsComeBack() throws Exception {
        mvc.perform(get("/v1/instruments/NOPE").header("X-Request-Id", "md-trace-1"))
                .andExpect(header().string("X-Request-Id", "md-trace-1"))
                .andExpect(jsonPath("$.requestId").value("md-trace-1"));
    }

    record Event(String name, JsonNode data) {}

    List<Event> readStream(String query, Duration duration) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream?" + query))
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<Event> events = new ArrayList<>();
        Instant end = Instant.now().plus(duration);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String name = null;
            String line;
            while (Instant.now().isBefore(end) && (line = in.readLine()) != null) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:") && name != null) {
                    events.add(new Event(name, json.readTree(line.substring(5))));
                    name = null;
                }
            }
        }
        return events;
    }

    @Test
    void theStreamStartsWithTheStateThenTicksWithRisingSeq() throws Exception {
        List<Event> events = readStream("symbols=HARBOR,INKWELL", Duration.ofSeconds(3));
        assertThat(events.get(0).name()).isEqualTo("market");
        assertThat(events.subList(1, 3)).extracting(Event::name).containsOnly("quote");
        List<Event> ticks = events.stream().filter(e -> e.name().equals("tick")).toList();
        assertThat(ticks).hasSizeGreaterThan(20);
        Map<String, Long> seq = new HashMap<>();
        events.subList(1, 3).forEach(q -> seq.put(q.data().path("symbol").asText(), q.data().path("seq").asLong()));
        for (Event t : ticks) {
            String sym = t.data().path("symbol").asText();
            assertThat(sym).isIn("HARBOR", "INKWELL");
            assertThat(t.data().path("seq").asLong()).as(sym).isGreaterThan(seq.get(sym));
            seq.put(sym, t.data().path("seq").asLong());
            assertThat(t.data().path("price").asDouble()).isPositive();
            assertThat(t.data().path("emittedAt").asText()).endsWith("Z");
        }
    }

    @Test
    void streamRequestsAreValidatedBeforeTheStreamStarts() throws Exception {
        mvc.perform(get("/v1/stream").param("symbols", "NOPE").header("Accept", "text/event-stream"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
        mvc.perform(get("/v1/stream").header("Accept", "text/event-stream"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
