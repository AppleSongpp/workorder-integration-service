package com.qts.sdp.workorder.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.qts.sdp.workorder.repository.IdempotencyRecordRepository;

/** Deletes idempotency records past their retention. Safe to run on several instances at once. */
@Component
public class IdempotencyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurger.class);

    private final IdempotencyRecordRepository repository;
    private final Clock clock;

    public IdempotencyPurger(IdempotencyRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${workorders.idempotency.purge-interval:PT15M}",
            initialDelayString = "${workorders.idempotency.purge-interval:PT15M}")
    @Transactional
    public int purgeExpired() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency records", deleted);
        }
        return deleted;
    }
}
