package com.medicity.patient;

import com.medicity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published API reference says how to act for a family member. The header
 * is read in ActingPatient, not by any controller, so it is documented by hand
 * and this keeps that from drifting.
 */
@AutoConfigureMockMvc
class ApiDocsTest extends AbstractIntegrationTest {

    private static final String HEADER = "$.paths['%s'].%s.parameters[?(@.name == 'X-Patient-Id')]";

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("X-Patient-Id is documented on the routes that act as the patient, and only there")
    void actingHeaderIsDocumented() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(HEADER.formatted("/api/v1/patients/me/prescriptions", "get"), hasSize(1)))
                .andExpect(jsonPath(HEADER.formatted("/api/v1/patients/me/medicine-requests", "post"), hasSize(1)))
                .andExpect(jsonPath(HEADER.formatted("/api/v1/appointments", "post"), hasSize(1)))
                .andExpect(jsonPath(HEADER.formatted("/api/v1/appointments/mine", "get"), hasSize(1)))
                // Managing the family is the account holder's, never a member's.
                .andExpect(jsonPath(HEADER.formatted("/api/v1/patients/me/family", "get")).doesNotExist())
                .andExpect(jsonPath(HEADER.formatted("/api/v1/stores/me/reservations", "get")).doesNotExist());
    }
}
