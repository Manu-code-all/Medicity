package com.medicity.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Messages API call and the parsing of its answer, against a mock server. */
@DisplayName("Reading handwriting with Claude")
class ClaudePrescriptionReaderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ClaudePrescriptionReader reader =
            new ClaudePrescriptionReader(builder.build(), json, "test-key", "claude-sonnet-5");

    @Test
    @DisplayName("sends the photo as a base64 image block with the key and API version, and parses the reply")
    void readsThePhoto() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.messages[0].content[0].type").value("image"))
                .andExpect(jsonPath("$.messages[0].content[0].source.media_type").value("image/png"))
                .andExpect(jsonPath("$.messages[0].content[0].source.data").value("iVBORw=="))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"```json\\n{\\"diagnosis\\":\\"GERD\\",\\"lines\\":[{\\"writtenAs\\":\\"Tab Omez 20 OD AC x 14d\\",\\"medicine\\":\\"Omez\\",\\"strength\\":\\"20mg\\",\\"dosage\\":\\"1 capsule\\",\\"frequency\\":\\"Once daily before food\\",\\"durationDays\\":14,\\"quantity\\":14,\\"confidence\\":0.9}],\\"unreadable\\":[\\"line 3\\"]}\\n```"}]}
                        """, MediaType.APPLICATION_JSON));

        PrescriptionReader.Reading reading = reader.read(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png");

        assertThat(reading.diagnosis()).isEqualTo("GERD");
        assertThat(reading.lines()).hasSize(1);
        assertThat(reading.lines().get(0).medicine()).isEqualTo("Omez");
        assertThat(reading.lines().get(0).durationDays()).isEqualTo(14);
        assertThat(reading.lines().get(0).frequency()).isEqualTo("Once daily before food");
        assertThat(reading.unreadable()).containsExactly("line 3");
        server.verify();
    }

    @Test
    @DisplayName("a reply that is not a reading, or an API error, fails cleanly instead of producing a draft")
    void failsCleanly() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"I cannot read this.\"}]}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> reader.read(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(PrescriptionReader.ReadingFailed.class);

        server.reset();
        server.expect(requestTo("https://api.anthropic.test/v1/messages")).andRespond(withServerError());
        assertThatThrownBy(() -> reader.read(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(PrescriptionReader.ReadingFailed.class);
    }

    @Test
    @DisplayName("without an API key the reader says it is unavailable")
    void unavailableWithoutKey() {
        assertThat(new ClaudePrescriptionReader(json, "", "claude-sonnet-5", "https://api.anthropic.test").available())
                .isFalse();
        assertThat(reader.available()).isTrue();
    }
}
