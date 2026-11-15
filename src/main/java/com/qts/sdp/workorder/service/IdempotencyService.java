package com.qts.sdp.workorder.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.config.IdempotencyProperties;
import com.qts.sdp.workorder.dto.WorkOrderView;
import com.qts.sdp.workorder.exception.IdempotencyKeyReuseException;
import com.qts.sdp.workorder.model.IdempotencyKey;
import com.qts.sdp.workorder.model.IdempotencyRecord;
import com.qts.sdp.workorder.repository.IdempotencyRecordRepository;
import com.qts.sdp.workorder.security.Caller;

/**
 * Remembers the outcome of each create request per (caller, Idempotency-Key) and hands it back
 * when the request is repeated. Knows nothing about how a work order is created; the
 * orchestration, including the race between concurrent requests, is in {@link WorkOrderService}.
 */
@Component
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository records, IdempotencyProperties properties,
            ObjectMapper objectMapper, Clock clock) {
        this.records = records;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * The response originally returned for this caller and key, if the key was used before.
     *
     * @param requestHash fingerprint of the current request, see {@link RequestFingerprint}
     * @throws IdempotencyKeyReuseException if the key was used before with a different payload
     */
    public Optional<WorkOrderView> findOriginalResponse(Caller caller, IdempotencyKey key, String requestHash) {
        Optional<IdempotencyRecord> stored =
                records.findByClientIdAndIdempotencyKey(caller.clientId(), key.value());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!stored.get().getRequestHash().equals(requestHash)) {
            log.warn("Idempotency key reused with a different payload: client={} idempotencyKey={}",
                    caller.clientId(), key);
            throw new IdempotencyKeyReuseException();
        }
        WorkOrderView original = fromJson(stored.get().getResponseBody());
        log.info("Idempotent replay: id={} client={} idempotencyKey={}", original.id(), caller.clientId(), key);
        return Optional.of(original);
    }

    /**
     * Stores the response for this caller and key. Must run inside the transaction that creates
     * the work order, so that both commit or neither does.
     *
     * <p>Flushes immediately so a duplicate key surfaces here, as a typed exception, and not at
     * commit. A concurrent transaction holding the same key makes this call wait until that
     * transaction ends, then fail on the unique constraint.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the key is already stored
     * @throws org.springframework.dao.ConcurrencyFailureException     if waiting for a concurrent
     *         request with the same key timed out
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Caller caller, IdempotencyKey key, String requestHash, Long workOrderId,
            WorkOrderView response) {
        Instant now = clock.instant();
        records.saveAndFlush(new IdempotencyRecord(caller.clientId(), key.value(), requestHash, workOrderId,
                HttpStatus.CREATED.value(), toJson(response), now, now.plus(properties.retention())));
    }

    private String toJson(WorkOrderView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize work order " + view.id(), e);
        }
    }

    private WorkOrderView fromJson(String json) {
        try {
            return objectMapper.readValue(json, WorkOrderView.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }
}
