package com.qts.sdp.workorder.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention     how long a key is remembered after the work order was created
 * @param purgeInterval how often expired keys are deleted (ISO-8601 duration; also read
 *                      directly by the scheduler in {@code IdempotencyPurger})
 */
@ConfigurationProperties("workorders.idempotency")
public record IdempotencyProperties(
        @DefaultValue("PT24H") Duration retention,
        @DefaultValue("PT15M") Duration purgeInterval) {
}
