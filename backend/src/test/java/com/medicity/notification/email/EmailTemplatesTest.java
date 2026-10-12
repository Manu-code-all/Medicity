package com.medicity.notification.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What the notification emails say")
class EmailTemplatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode data(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("a visit email names the doctor and the time in India, which the reader needs to act")
    void visitEmail() throws Exception {
        var body = EmailTemplates.body(EmailTemplates.BOOKED, data("""
                {"doctorName":"Dr. Anjali Rao","forName":"","scheduledAt":"2030-01-07T04:30:00Z"}"""));

        assertThat(body.subject()).isEqualTo("Your Medicity appointment is confirmed");
        assertThat(body.text()).isEqualTo(
                "Your appointment with Dr. Anjali Rao is confirmed for Mon 7 Jan, 10:00 AM IST.");
    }

    @Test
    @DisplayName("an email about a family member says whose visit it is")
    void familyMember() throws Exception {
        var body = EmailTemplates.body(EmailTemplates.REMINDER, data("""
                {"doctorName":"Dr. Rao","forName":"Aarav","scheduledAt":"2030-01-07T04:30:00Z"}"""));

        assertThat(body.text()).startsWith("Aarav's appointment with Dr. Rao");
    }

    @Test
    @DisplayName("prescription emails carry no doctor, diagnosis or medicine: only 'sign in to view'")
    void prescriptionEmailsAreBare() throws Exception {
        // Even if the data held health details, the template has no place for them.
        JsonNode rich = data("""
                {"doctorName":"Dr. Anjali Rao","diagnosis":"Type 2 diabetes","medicine":"Metformin","forName":"Aarav"}""");

        for (String template : new String[]{EmailTemplates.PRESCRIPTION_ISSUED, EmailTemplates.PRESCRIPTION_CORRECTED}) {
            var body = EmailTemplates.body(template, rich);
            String everything = body.subject() + " " + body.text();
            assertThat(everything).doesNotContain("Rao", "diabetes", "Metformin", "Aarav");
            assertThat(body.text()).contains("Sign in");
        }
        assertThat(EmailTemplates.body(EmailTemplates.PRESCRIPTION_ISSUED, rich).text())
                .isEqualTo("You have a new prescription. Sign in to view it.");
    }

    @Test
    @DisplayName("the doctor-away email never includes a leave note")
    void awayEmailHasNoNote() throws Exception {
        var body = EmailTemplates.body(EmailTemplates.DOCTOR_AWAY, data("""
                {"doctorName":"Dr. Rao","note":"Surgery for my knee","scheduledAt":"2030-01-07T04:30:00Z"}"""));

        assertThat(body.text()).contains("Dr. Rao will be away").doesNotContain("knee");
    }

    @Test
    @DisplayName("an unknown template is null, so a newer deploy's event is skipped rather than failing forever")
    void unknownTemplate() throws Exception {
        assertThat(EmailTemplates.body("SOMETHING_NEW", data("{}"))).isNull();
    }

    @Test
    @DisplayName("names are escaped in the HTML version, and the footer offers the unsubscribe link")
    void htmlIsEscapedAndHasFooter() throws Exception {
        var body = EmailTemplates.body(EmailTemplates.BOOKED, data("""
                {"doctorName":"Dr. <script>alert(1)</script>","scheduledAt":"2030-01-07T04:30:00Z"}"""));

        String html = EmailTemplates.html(body, "https://app.test", "https://api.test/u?u=1&t=2");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(html).contains("href=\"https://api.test/u?u=1&amp;t=2\"").contains("href=\"https://app.test\"");
        assertThat(EmailTemplates.text(body, "https://app.test", "https://api.test/u?u=1&t=2"))
                .contains("turn these emails off in your profile, or here: https://api.test/u?u=1&t=2");
    }

    @Test
    @DisplayName("without public addresses the footer still says where to turn emails off")
    void footerWithoutLinks() throws Exception {
        var body = EmailTemplates.body(EmailTemplates.CANCELLED, data("{}"));

        assertThat(EmailTemplates.text(body, "", "")).contains("turn these emails off in your profile.")
                .doesNotContain("http");
    }
}
