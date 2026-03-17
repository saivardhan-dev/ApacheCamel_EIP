package com.apachecamel.mini_integration_platform.service;

import com.apachecamel.mini_integration_platform.model.RouteConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Service;

import static com.apachecamel.mini_integration_platform.processor.ScenarioProcessor.ROUTING_SLIP_SCENARIO;

/**
 * DynamicQueueResolver
 *
 * Resolves target queue names dynamically from the message payload
 * and routing headers using the DYNAMIC_TYPE_AMOUNT strategy.
 *
 * Queue pattern placeholders:
 *   {SCENARIO} → ScenarioName from RoutingSlip header  e.g. SCENARIO1
 *   {TYPE}     → type field from payload               e.g. CARS
 *   {BRACKET}  → HIGHVALUE or LOWVALUE from amount     e.g. HIGHVALUE
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Pattern: GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.{BRACKET}.1.OUT         │
 * │                                                                      │
 * │  Scenario1 + Cars        + 2000 → GATEWAY.EXIT.WW.SCENARIO1.CARS.HIGHVALUE.1.OUT │
 * │  Scenario1 + Cars        + 999  → GATEWAY.EXIT.WW.SCENARIO1.CARS.LOWVALUE.1.OUT  │
 * │  Scenario2 + Electronics + 1500 → GATEWAY.EXIT.WW.SCENARIO2.ELECTRONICS.HIGHVALUE.1.OUT │
 * │  Scenario2 + Furniture   + 800  → GATEWAY.EXIT.WW.SCENARIO2.FURNITURE.LOWVALUE.1.OUT    │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Type-only pattern (ignores amount bracket):
 *   GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT
 *   Scenario1 + Cars        → GATEWAY.EXIT.WW.SCENARIO1.CARS.1.OUT
 *   Scenario2 + Electronics → GATEWAY.EXIT.WW.SCENARIO2.ELECTRONICS.1.OUT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicQueueResolver {

  private final ObjectMapper objectMapper;

  private static final String PLACEHOLDER_SCENARIO = "{SCENARIO}";
  private static final String PLACEHOLDER_TYPE     = "{TYPE}";
  private static final String PLACEHOLDER_BRACKET  = "{BRACKET}";

  // Type-only pattern — bracket ignored, scenario included
  private static final String TYPE_ONLY_PATTERN =
          "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT";

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Resolves the dynamic target queue from the message payload and
   * the scenario name from the exchange RoutingSlip headers.
   *
   * @param payload      raw JSON message body
   * @param routeConfig  current route leg config from Scenarios.json
   * @param exchange     the Camel exchange — used to read scenario header
   * @return             fully resolved queue name
   */
  public String resolve(String payload, RouteConfig routeConfig, Exchange exchange) {
    try {
      JsonNode node = objectMapper.readTree(payload);

      // ── Read scenario name from RoutingSlip header ─────────────────────
      String scenarioRaw = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
      String scenario    = (scenarioRaw != null)
              ? scenarioRaw.toUpperCase().replace(" ", "")
              : "UNKNOWN";

      // ── Read type field ────────────────────────────────────────────────
      if (!node.has("type") || node.get("type").asText().isBlank()) {
        throw new NumberFormatException(
                "Cannot resolve dynamic queue — 'type' field is missing or blank"
        );
      }
      String type = node.get("type").asText().trim().toUpperCase();

      // ── Read amount field ──────────────────────────────────────────────
      if (!node.has("amount")) {
        throw new NumberFormatException(
                "Cannot resolve dynamic queue — 'amount' field is required " +
                        "for DYNAMIC_TYPE_AMOUNT routing strategy"
        );
      }

      double amount;
      JsonNode amountNode = node.get("amount");
      if (amountNode.isNumber()) {
        amount = amountNode.asDouble();
      } else {
        try {
          amount = Double.parseDouble(amountNode.asText());
        } catch (NumberFormatException e) {
          throw new NumberFormatException(
                  "Cannot resolve dynamic queue — 'amount' value '" +
                          amountNode.asText() + "' is not numeric"
          );
        }
      }

      // ── Determine HIGH or LOW bracket ──────────────────────────────────
      double threshold = routeConfig.getAmountThreshold();
      String bracket   = (amount >= threshold)
              ? routeConfig.getHighValueSuffix()
              : routeConfig.getLowValueSuffix();

      // ── Build queue name from pattern ──────────────────────────────────
      String queueName = routeConfig.getQueuePattern()
              .replace(PLACEHOLDER_SCENARIO, scenario)
              .replace(PLACEHOLDER_TYPE,     type)
              .replace(PLACEHOLDER_BRACKET,  bracket);

      log.info("[DynamicQueueResolver] Resolved — scenario='{}' type='{}' " +
                      "amount={} threshold={} bracket='{}' queue='{}'",
              scenario, type, amount, threshold, bracket, queueName);

      return queueName;

    } catch (NumberFormatException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
              "Failed to resolve dynamic queue from payload — " + e.getMessage(), e
      );
    }
  }

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Resolves the type-only queue — amount is ignored, scenario is included.
   * All messages of the same type within the same scenario land here.
   *
   * Scenario1 + Cars        → GATEWAY.EXIT.WW.SCENARIO1.CARS.1.OUT
   * Scenario2 + Electronics → GATEWAY.EXIT.WW.SCENARIO2.ELECTRONICS.1.OUT
   * Scenario2 + Furniture   → GATEWAY.EXIT.WW.SCENARIO2.FURNITURE.1.OUT
   *
   * @param payload   raw JSON message body
   * @param exchange  the Camel exchange — used to read scenario header
   * @return          type-only queue name, or null if type cannot be read
   */
  public String resolveTypeOnlyQueue(String payload, Exchange exchange) {
    try {
      JsonNode node = objectMapper.readTree(payload);

      if (!node.has("type") || node.get("type").asText().isBlank()) {
        log.warn("[DynamicQueueResolver] resolveTypeOnlyQueue — 'type' field missing");
        return null;
      }

      String scenarioRaw = exchange.getIn().getHeader(ROUTING_SLIP_SCENARIO, String.class);
      String scenario    = (scenarioRaw != null)
              ? scenarioRaw.toUpperCase().replace(" ", "")
              : "UNKNOWN";

      String type      = node.get("type").asText().trim().toUpperCase();
      String queueName = TYPE_ONLY_PATTERN
              .replace(PLACEHOLDER_SCENARIO, scenario)
              .replace(PLACEHOLDER_TYPE,     type);

      log.info("[DynamicQueueResolver] Type-only queue — scenario='{}' type='{}' queue='{}'",
              scenario, type, queueName);

      return queueName;

    } catch (Exception e) {
      log.error("[DynamicQueueResolver] resolveTypeOnlyQueue failed — {}", e.getMessage(), e);
      return null;
    }
  }
}