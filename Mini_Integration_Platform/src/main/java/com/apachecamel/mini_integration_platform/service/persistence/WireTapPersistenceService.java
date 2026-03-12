package com.apachecamel.mini_integration_platform.service.persistence;

import com.apachecamel.mini_integration_platform.model.document.WireTapDocument;
import com.apachecamel.mini_integration_platform.processor.ScenarioProcessor;
import com.apachecamel.mini_integration_platform.repository.WireTapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * WireTapPersistenceService
 *
 * Called directly inside .wireTap().onPrepare() — NO queues involved.
 *
 * Since onPrepare already runs on a separate thread (that is the whole
 * point of wireTap), we can call this service directly and save straight
 * to MongoDB without any intermediate AMQ queues or consumer routes.
 *
 * Usage in a route:
 *
 *   .wireTap("log:wiretap")
 *       .onPrepare(ex -> wireTapPersistenceService.save(ex, "ROUTE1_ENTRY"))
 *   .end()
 *
 * The tapPoint parameter identifies WHERE in the flow this tap was taken:
 *   "ROUTE1_ENTRY"           — after ScenarioProcessor stamps headers
 *   "ROUTE2_POST_VALIDATION" — after MessageValidatorProcessor passes
 *   "EXIT"                   — after message delivered to exit queue
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WireTapPersistenceService {

  private final WireTapRepository wireTapRepository;

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Reads routing headers from the exchange and saves a WireTapDocument
   * directly to MongoDB "wiretaps" collection.
   *
   * @param exchange  the wire-tapped copy of the exchange (runs on separate thread)
   * @param tapPoint  identifier for where in the flow this tap was taken
   */
  public void save(Exchange exchange, String tapPoint) {
    try {
      WireTapDocument document = WireTapDocument.builder()
              .tapPoint         (tapPoint)
              .originalMessageId(exchange.getIn().getHeader(ScenarioProcessor.ORIGINAL_MESSAGE_ID,  String.class))
              .messageId        (exchange.getIn().getMessageId())
              .scenarioName     (exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_SCENARIO, String.class))
              .countryCode      (exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_COUNTRY,  String.class))
              .instanceId       (exchange.getIn().getHeader(ScenarioProcessor.ROUTING_SLIP_INSTANCE, 0, Integer.class))
              .routeName        (exchange.getIn().getHeader(ScenarioProcessor.CURRENT_ROUTE_NAME,    String.class))
              .sourceQueue      (exchange.getIn().getHeader(ScenarioProcessor.ROUTE_SOURCE,          String.class))
              .targetQueue      (exchange.getIn().getHeader(ScenarioProcessor.ROUTE_TARGET,          String.class))
              .payload          (exchange.getIn().getBody(String.class))
              .tappedAt         (Instant.now())
              .build();

      WireTapDocument saved = wireTapRepository.save(document);

      log.info("[WireTapPersistenceService] Saved — tapPoint='{}' id='{}' msgId='{}' scenario='{}'",
              tapPoint, saved.getId(),
              saved.getOriginalMessageId(), saved.getScenarioName());

    } catch (Exception e) {
      log.error("[WireTapPersistenceService] Failed to save wiretap — tapPoint='{}' error='{}'",
              tapPoint, e.getMessage(), e);
    }
  }
}