package com.medicity.common;

import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract: a bad request is the caller's fault and must say so.
 *
 * <p>This class exists because production answered every unknown URL with a
 * 500. Spring reports framework-level client errors (no such route, wrong
 * method, unreadable body) as exceptions, and a catch-all handler turned each
 * one into an "internal error" — telling clients the server was broken and
 * logging a stack trace as an incident for every typo.
 */
@AutoConfigureMockMvc
@DisplayName("Error contract")
class ErrorContractTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private String token;

    @BeforeEach
    void setUp() {
        // An admin needs no patient or doctor profile, so this user leaves
        // nothing behind that other suites' cleanup has to know about.
        User admin = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName("Ops Admin")
                .role(Role.ADMIN)
                .enabled(true)
                .build();
        admin.setEmail("admin-" + UUID.randomUUID() + "@medicity.test");
        token = jwtService.issueAccessToken(userRepository.save(admin));
    }

    @Test
    @DisplayName("an unknown route is 404, not 500")
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.incidentId").doesNotExist());
    }

    @Test
    @DisplayName("a wrong method is 405 and keeps the Allow header")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(delete("/api/v1/doctors").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    @DisplayName("a body that is not JSON is 400, without echoing parser internals")
    void malformedBodyIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("The request body could not be read"));
    }

    @Test
    @DisplayName("a malformed path id is 400")
    void malformedIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/doctors/not-a-uuid/slots")
                        .param("from", "2030-01-01T00:00:00Z")
                        .param("to", "2030-01-02T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("a missing required parameter is 400")
    void missingParameterIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/doctors/" + UUID.randomUUID() + "/slots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("error type URIs live under /errors/, as documented")
    void typeUriIsUnderErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://medicity.dev/errors/validation-failed"));
    }
}
