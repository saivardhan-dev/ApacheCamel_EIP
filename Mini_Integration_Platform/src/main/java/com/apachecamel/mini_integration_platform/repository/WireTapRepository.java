package com.apachecamel.mini_integration_platform.repository;

import com.apachecamel.mini_integration_platform.model.document.WireTapDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * WireTapRepository
 *
 * Spring Data MongoDB repository for the "wiretaps" collection.
 *
 * Useful queries:
 *   - Trace a full message journey by originalMessageId
 *   - Find all taps at a specific point (ROUTE1_ENTRY, ROUTE2_POST_VALIDATION, EXIT)
 *   - Find all tapped messages for a scenario
 */
@Repository
public interface WireTapRepository extends MongoRepository<WireTapDocument, String> {

  /** Full message journey — returns all tap snapshots for one message (should be 3) */
  List<WireTapDocument> findByOriginalMessageIdOrderByTappedAtAsc(String originalMessageId);

  /** All messages tapped at a specific point in the flow */
  List<WireTapDocument> findByTapPoint(String tapPoint);

  /** All wire tap records for a specific scenario */
  List<WireTapDocument> findByScenarioName(String scenarioName);

  /** Combined — taps for a scenario at a specific tap point */
  List<WireTapDocument> findByScenarioNameAndTapPoint(String scenarioName, String tapPoint);
}