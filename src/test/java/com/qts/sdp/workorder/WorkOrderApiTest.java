package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.qts.sdp.workorder.devtools.TestCaller;

/** Work-order creation, retrieval, status changes, invalid input and invalid transitions. */
class WorkOrderApiTest extends IntegrationTest {

    private static final String SDP = bearer(TestCaller.SDP);

    @Test
    void createsWorkOrder() throws Exception {
        postWorkOrder(SDP, newKey(), createBody("SDP", "Cooling unit requires maintenance"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("/api/workorders/WO-\\d+")))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.id", matchesPattern("WO-\\d+")))
                .andExpect(jsonPath("$.sourceSystem").value("SDP"))
                .andExpect(jsonPath("$.description").value("Cooling unit requires maintenance"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void retrievesWorkOrder() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        getWorkOrder(SDP, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.sourceSystem").value("SDP"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void movesThroughTheLifecycleAndIncrementsVersion() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        patchStatus(TestCaller.SDP, id, "IN_PROGRESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2));
        patchStatus(TestCaller.SDP, id, "COMPLETED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(3));
        getWorkOrder(SDP, id)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void workInProgressCanBeReopened() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");
        patchStatus(TestCaller.SDP, id, "IN_PROGRESS").andExpect(status().isOk());

        patchStatus(TestCaller.SDP, id, "OPEN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void settingTheCurrentStatusAgainIsANoOp() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");
        patchStatus(TestCaller.SDP, id, "IN_PROGRESS").andExpect(status().isOk());

        patchStatus(TestCaller.SDP, id, "IN_PROGRESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(outboxPayloads(id)).hasSize(1);
    }

    @Test
    void rejectsSkippingInProgress() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        patchStatus(TestCaller.SDP, id, "COMPLETED")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("OPEN"))
                .andExpect(jsonPath("$.allowedStatuses", contains("IN_PROGRESS")));
        getWorkOrder(SDP, id)
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(outboxPayloads(id)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "IN_PROGRESS"})
    void completedIsTerminal(String target) throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");
        patchStatus(TestCaller.SDP, id, "IN_PROGRESS").andExpect(status().isOk());
        patchStatus(TestCaller.SDP, id, "COMPLETED").andExpect(status().isOk());

        patchStatus(TestCaller.SDP, id, target)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void rejectsStatusChangeBasedOnStaleVersion() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");
        patchStatus(SDP, id, "{\"status\":\"IN_PROGRESS\",\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        patchStatus(SDP, id, "{\"status\":\"COMPLETED\",\"version\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        getWorkOrder(SDP, id).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void statusChangeRecordsAnEventInTheOutbox() throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        patchStatus(TestCaller.SDP, id, "IN_PROGRESS").andExpect(status().isOk());
        patchStatus(TestCaller.SDP, id, "COMPLETED").andExpect(status().isOk());

        List<String> payloads = outboxPayloads(id);
        assertThat(payloads).hasSize(2);
        JsonNode first = json(payloads.get(0));
        assertThat(first.get("eventId").asString()).isNotBlank();
        assertThat(first.get("eventType").asString()).isEqualTo("WorkOrderStatusChanged");
        assertThat(first.get("workOrderId").asString()).isEqualTo(id);
        assertThat(first.get("sourceSystem").asString()).isEqualTo("SDP");
        assertThat(first.get("previousStatus").asString()).isEqualTo("OPEN");
        assertThat(first.get("newStatus").asString()).isEqualTo("IN_PROGRESS");
        assertThat(first.get("workOrderVersion").asLong()).isEqualTo(2);
        JsonNode second = json(payloads.get(1));
        assertThat(second.get("newStatus").asString()).isEqualTo("COMPLETED");
        assertThat(second.get("workOrderVersion").asLong()).isEqualTo(3);
        assertThat(second.get("eventId").asString()).isNotEqualTo(first.get("eventId").asString());
    }

    // ---- invalid input ----

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sourceSystem\":\"SDP\"}",
            "{\"sourceSystem\":\"SDP\",\"description\":\"\"}",
            "{\"sourceSystem\":\"SDP\",\"description\":\"   \"}"
    })
    void rejectsMissingOrBlankDescription(String body) throws Exception {
        postWorkOrder(SDP, newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("description"));
    }

    @Test
    void rejectsTooLongDescription() throws Exception {
        postWorkOrder(SDP, newKey(), createBody("SDP", "x".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMissingSourceSystem() throws Exception {
        postWorkOrder(SDP, newKey(), "{\"description\":\"No owner\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sourceSystem"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sourceSystem\":\"UNKNOWN\",\"description\":\"d\"}",
            // a number must not be read as an enum position (0 = SDP, 1 = QTSOS)
            "{\"sourceSystem\":0,\"description\":\"d\"}",
            "{\"sourceSystem\":1,\"description\":\"d\"}",
            "{\"sourceSystem\":\"SDP\",\"description\":\"d\",\"status\":\"COMPLETED\"}",
            "{\"sourceSystem\":\"SDP\",\"description\":\"d\",\"id\":\"WO-1\"}",
            "{\"sourceSystem\":\"SDP\",",
            ""
    })
    void rejectsUnknownValuesUnknownFieldsAndMalformedJson(String body) throws Exception {
        postWorkOrder(SDP, newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsCreateWithoutIdempotencyKey() throws Exception {
        mvc.perform(post("/api/workorders")
                        .header(HttpHeaders.AUTHORIZATION, SDP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SDP", "No key")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "semi;colon", "slash/key"})
    void rejectsMalformedIdempotencyKey(String key) throws Exception {
        postWorkOrder(SDP, key, createBody("SDP", "Bad key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void rejectsTooLongIdempotencyKey() throws Exception {
        postWorkOrder(SDP, "k".repeat(129), createBody("SDP", "Bad key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":\"CANCELLED\"}", "{\"status\":1}",
            "{\"status\":\"OPEN\",\"version\":0}", "not json"})
    void rejectsInvalidStatusRequest(String body) throws Exception {
        String id = givenWorkOrder(TestCaller.SDP, "SDP");

        patchStatus(SDP, id, body).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WO-999999999", "WO-abc", "1001", "WO-0"})
    void unknownOrMalformedIdIsNotFound(String id) throws Exception {
        getWorkOrder(SDP, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        patchStatus(TestCaller.SDP, id, "IN_PROGRESS")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
    }

    private List<String> outboxPayloads(String workOrderId) {
        return jdbc.queryForList("select payload from outbox_event where aggregate_id = ? order by created_at",
                String.class, workOrderId);
    }
}
