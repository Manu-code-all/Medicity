package com.medicity.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Gemini call, and how the application chooses between readers. */
@DisplayName("Reading handwriting with Gemini, and choosing a reader")
class GeminiPrescriptionReaderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GeminiPrescriptionReader reader =
            new GeminiPrescriptionReader(builder.build(), json, "test-key", "gemini-2.5-flash");

    @Test
    @DisplayName("sends the photo inline with the key in a header, asks for JSON, and parses the reply")
    void readsThePhoto() {
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[0].parts[0].inlineData.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.contents[0].parts[0].inlineData.data").value("AQI="))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value(ReadingParser.INSTRUCTIONS))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"diagnosis\\":\\"Viral fever\\",\\"lines\\":[{\\"writtenAs\\":\\"Tab Dolo 650 TDS x 3d\\",\\"medicine\\":\\"Dolo\\",\\"strength\\":\\"650mg\\",\\"dosage\\":\\"1 tablet\\",\\"frequency\\":\\"Three times daily\\",\\"durationDays\\":3,\\"quantity\\":9,\\"confidence\\":0.85}],\\"unreadable\\":[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        PrescriptionReader.Reading reading = reader.read(new byte[]{1, 2}, "image/jpeg");

        assertThat(reading.diagnosis()).isEqualTo("Viral fever");
        assertThat(reading.lines()).singleElement().satisfies(l -> {
            assertThat(l.medicine()).isEqualTo("Dolo");
            assertThat(l.quantity()).isEqualTo(9);
        });
        server.verify();
    }

    @Test
    @DisplayName("a rate limit or a reply that is not a reading fails cleanly")
    void failsCleanly() {
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> reader.read(new byte[]{1}, "image/png"))
                .isInstanceOf(PrescriptionReader.ReadingFailed.class);

        server.reset();
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Sorry.\"}]}}]}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> reader.read(new byte[]{1}, "image/png"))
                .isInstanceOf(PrescriptionReader.ReadingFailed.class);
    }

    @Test
    @DisplayName("the application uses whichever reader has a key, and falls back to the next if one fails")
    void choosesAndFallsBack() {
        AtomicInteger firstCalls = new AtomicInteger();
        PrescriptionReader.Reading fromSecond = new PrescriptionReader.Reading("GERD", List.of(), List.of());
        PrescriptionReader failing = fake(true, () -> {
            firstCalls.incrementAndGet();
            throw new PrescriptionReader.ReadingFailed("rate limited", null);
        });
        PrescriptionReader working = fake(true, () -> fromSecond);
        PrescriptionReader noKey = fake(false, () -> {
            throw new AssertionError("a reader without a key must not be called");
        });

        assertThat(new ConfiguredPrescriptionReader(List.of(noKey, working)).read(new byte[]{1}, "image/png"))
                .isSameAs(fromSecond);
        assertThat(new ConfiguredPrescriptionReader(List.of(failing, working)).read(new byte[]{1}, "image/png"))
                .isSameAs(fromSecond);
        assertThat(firstCalls).hasValue(1);

        ConfiguredPrescriptionReader none = new ConfiguredPrescriptionReader(List.of(noKey));
        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> new ConfiguredPrescriptionReader(List.of(failing)).read(new byte[]{1}, "image/png"))
                .isInstanceOf(PrescriptionReader.ReadingFailed.class)
                .hasMessage("rate limited");
    }

    private static PrescriptionReader fake(boolean available, java.util.function.Supplier<PrescriptionReader.Reading> read) {
        return new PrescriptionReader() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public Reading read(byte[] image, String contentType) {
                return read.get();
            }
        };
    }
}
