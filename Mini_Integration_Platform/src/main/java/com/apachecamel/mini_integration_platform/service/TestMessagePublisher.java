package com.apachecamel.mini_integration_platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * TestMessagePublisher
 *
 * Runs automatically after the Spring context and all Camel routes are fully started.
 * Posts two test messages to Scenario1's source queue:
 *
 *   Message 1 — HAPPY PATH  →  {"type":"ORDER","data":"test-order-001"}
 *               Expected:  flows through Route1 → Route2 → exit queue → 2 Audit messages
 *
 *   Message 2 — EXCEPTION PATH  →  {"type":"ERROR","data":"test-error-001"}
 *               Expected:  flows through Route1 (ok) → Route2 → MessageValidatorProcessor
 *               throws RuntimeException → ExceptionProcessor fires →
 *               Exception message sent to COMMON.EXCEPTION.SERVICE.IN
 *
 * Uses ApplicationRunner so messages are published AFTER all routes are live.
 * Remove or comment out this class in production.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class TestMessagePublisher implements ApplicationRunner {

    private final ProducerTemplate producerTemplate;

    private static final String SCENARIO1_SOURCE_QUEUE = "GATEWAY.ENTRY.WW.SCENARIO1.1.IN";

    @Override
    public void run(ApplicationArguments args) throws Exception {

        // Small delay to ensure all routes are fully consuming
        Thread.sleep(2000);

        log.info("========================================================");
        log.info("  TestMessagePublisher: Sending test messages");
        log.info("========================================================");

        // ── Message 1: Happy Path ─────────────────────────────────────────────
        String happyMessage = """
                {
                  "type": "ORDER",
                  "data": "test-order-001",
                  "amount": 250.00,
                  "currency": "USD"
                }
                """;

        log.info(">> Sending HAPPY PATH message to {}", SCENARIO1_SOURCE_QUEUE);
        producerTemplate.sendBody("activemq:" + SCENARIO1_SOURCE_QUEUE, happyMessage);
        log.info(">> Happy path message sent");

        // Small gap between messages
        Thread.sleep(1000);

        // ── Message 2: Exception Path ─────────────────────────────────────────
        // type=ERROR will cause MessageValidatorProcessor to throw RuntimeException
        String errorMessage = """
                {
                  "type": "ERROR",
                  "data": "test-error-001",
                  "amount": 999.99,
                  "currency": "USD"
                }
                """;

        log.info(">> Sending EXCEPTION PATH message to {}", SCENARIO1_SOURCE_QUEUE);
        producerTemplate.sendBody("activemq:" + SCENARIO1_SOURCE_QUEUE, errorMessage);
        log.info(">> Exception path message sent");

        log.info("========================================================");
        log.info("  TestMessagePublisher: Done. Watch the logs for results.");
        log.info("  Happy path  → check COMMON.AUDIT.SERVICE.IN");
        log.info("  Error path  → check COMMON.EXCEPTION.SERVICE.IN");
        log.info("========================================================");
    }
}













