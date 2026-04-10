/**
 * Distributed coordination contracts: cluster quarantine, throttle, training export (interfaces and DTOs).
 * <p>
 * Default implementations are no-ops where applicable; Redis/Kafka-backed beans are wired from the Spring Boot starter
 * when enabled. Scoring and local enforcement remain authoritative; distributed views are additive and fail-open.
 */
package io.aisentinel.distributed;
