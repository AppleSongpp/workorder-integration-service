package com.qts.sdp.workorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.qts.sdp.workorder.devtools.TestCaller;

/**
 * Base for tests that run the whole application: real security filter chain with real signed
 * tokens, real database, real HTTP port (used by the concurrency test). All subclasses share
 * one application context, so tests never assume an empty database: each one creates its own
 * work orders with fresh idempotency keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void identityProviderKeys(DynamicPropertyRegistry registry) {
        TestIdps.registerKeyLocations(registry);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String bearer(TestCaller caller) {
        return bearer(TestIdps.tokenFor(caller));
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static String createBody(String sourceSystem, String description) {
        return "{\"sourceSystem\":\"" + sourceSystem + "\",\"description\":\"" + description + "\"}";
    }

    protected ResultActions postWorkOrder(String authorization, String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/api/workorders")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected ResultActions getWorkOrder(String authorization, String id) throws Exception {
        return mvc.perform(get("/api/workorders/{id}", id).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ResultActions patchStatus(String authorization, String id, String body) throws Exception {
        return mvc.perform(patch("/api/workorders/{id}/status", id)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected ResultActions patchStatus(TestCaller caller, String id, String status) throws Exception {
        return patchStatus(bearer(caller), id, "{\"status\":\"" + status + "\"}");
    }

    /** Creates a work order as the caller that owns {@code sourceSystem} and returns its id. */
    protected String givenWorkOrder(TestCaller owner, String sourceSystem) throws Exception {
        String response = postWorkOrder(bearer(owner), newKey(), createBody(sourceSystem, "Test work order"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json(response).get("id").asText();
    }

    protected JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    protected int countWorkOrdersWithDescription(String description) {
        Integer count = jdbc.queryForObject("select count(*) from work_order where description = ?",
                Integer.class, description);
        return count == null ? 0 : count;
    }
}
