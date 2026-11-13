package com.qts.sdp.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.qts.sdp.workorder.devtools.TestCaller;
import com.qts.sdp.workorder.service.IdempotencyPurger;

/** Repeated requests with the same Idempotency-Key (sequential; see ConcurrentCreationTest). */
class IdempotencyTest extends IntegrationTest {

    private static final String SDP = bearer(TestCaller.SDP);

    @Autowired
    private IdempotencyPurger purger;

    @Test
    void repeatingARequestReturnsTheOriginalResultWithoutCreatingAnotherWorkOrder() throws Exception {
        String key = newKey();
        String description = "idem-repeat-" + key;
        String body = createBody("SDP", description);

        MvcResult first = postWorkOrder(SDP, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        MvcResult second = postWorkOrder(SDP, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        MvcResult third = postWorkOrder(SDP, key, body).andExpect(status().isCreated()).andReturn();

        String original = first.getResponse().getContentAsString();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(original);
        assertThat(third.getResponse().getContentAsString()).isEqualTo(original);
        assertThat(second.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(countWorkOrdersWithDescription(description)).isEqualTo(1);
    }

    @Test
    void replayReturnsTheOriginalResponseEvenAfterTheWorkOrderChanged() throws Exception {
        String key = newKey();
        String body = createBody("SDP", "idem-original-" + key);
        String original = postWorkOrder(SDP, key, body).andReturn().getResponse().getContentAsString();
        String id = json(original).get("id").asText();
        patchStatus(TestCaller.SDP, id, "IN_PROGRESS").andExpect(status().isOk());

        postWorkOrder(SDP, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void sameRequestWithDifferentJsonFormattingIsStillTheSameRequest() throws Exception {
        String key = newKey();
        String description = "idem-format-" + key;

        String first = postWorkOrder(SDP, key, createBody("SDP", description))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String reordered = "{ \"description\" : \"" + description + "\",\n  \"sourceSystem\" : \"SDP\" }";

        postWorkOrder(SDP, key, reordered)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(json(first).get("id").asText()));
        assertThat(countWorkOrdersWithDescription(description)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedWithConflict() throws Exception {
        String key = newKey();
        String otherDescription = "idem-conflict-other-" + key;
        postWorkOrder(SDP, key, createBody("SDP", "idem-conflict-" + key)).andExpect(status().isCreated());

        postWorkOrder(SDP, key, createBody("SDP", otherDescription))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countWorkOrdersWithDescription(otherDescription)).isZero();
    }

    @Test
    void keysAreScopedToTheCaller() throws Exception {
        String sharedKey = newKey();

        String sdpResponse = postWorkOrder(SDP, sharedKey, createBody("SDP", "idem-scope-" + sharedKey))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // Another caller using the same key with another payload is neither a replay nor a conflict.
        String qtsosResponse = postWorkOrder(bearer(TestCaller.QTSOS), sharedKey,
                createBody("QTSOS", "idem-scope-qtsos-" + sharedKey))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json(qtsosResponse).get("id").asText()).isNotEqualTo(json(sdpResponse).get("id").asText());
    }

    @Test
    void rejectedRequestsDoNotConsumeTheKey() throws Exception {
        String key = newKey();
        postWorkOrder(SDP, key, "{\"sourceSystem\":\"SDP\"}").andExpect(status().isBadRequest());
        postWorkOrder(SDP, key, createBody("QTSOS", "Not mine")).andExpect(status().isForbidden());

        postWorkOrder(SDP, key, createBody("SDP", "idem-after-failure-" + key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));
    }

    @Test
    void keyCanBeUsedAgainOnceItExpiredAndWasPurged() throws Exception {
        String key = newKey();
        String body = createBody("SDP", "idem-expiry-" + key);
        String first = postWorkOrder(SDP, key, body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        purger.purgeExpired();
        postWorkOrder(SDP, key, body)
                .andExpect(header().string("Idempotency-Replayed", "true"));

        // Simulate the retention period passing for this key.
        int updated = jdbc.update("update idempotency_record set expires_at = ? where idempotency_key = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), key);
        assertThat(updated).isEqualTo(1);
        assertThat(purger.purgeExpired()).isGreaterThanOrEqualTo(1);

        String afterExpiry = postWorkOrder(SDP, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json(afterExpiry).get("id").asText()).isNotEqualTo(json(first).get("id").asText());
    }
}
