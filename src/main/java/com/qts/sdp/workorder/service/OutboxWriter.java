package com.qts.sdp.workorder.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.event.WorkOrderStatusChangedEvent;
import com.qts.sdp.workorder.model.OutboxEvent;

/**
 * Stores integration events in the outbox table. Must be called inside the transaction that
 * makes the state change, so the event exists if and only if the change was committed.
 * Relaying outbox rows to a broker is out of scope here (see DESIGN.md §4).
 */
@Component
public class OutboxWriter {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public OutboxWriter(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(WorkOrderStatusChangedEvent event) {
        entityManager.persist(new OutboxEvent(event.eventId(), event.eventType(), event.workOrderId(),
                toJson(event), event.occurredAt()));
    }

    private String toJson(WorkOrderStatusChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize event " + event.eventId(), e);
        }
    }
}
