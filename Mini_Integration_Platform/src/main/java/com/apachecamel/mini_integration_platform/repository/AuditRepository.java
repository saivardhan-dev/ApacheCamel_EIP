package com.apachecamel.mini_integration_platform.repository;

import com.apachecamel.mini_integration_platform.model.document.AuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AuditRepository
 *
 * Spring Data MongoDB repository for the "audits" collection.
 * Provides CRUD operations and custom finder methods.
 *
 * Collection : audits
 * Document   : AuditDocument
 * Key type   : String (MongoDB ObjectId as String)
 */
@Repository
public interface AuditRepository extends MongoRepository<AuditDocument, String> {

    /**
     * Find all audit records for a given original message.
     * A single message produces 2 audit records (Route1 + Route2).
     * Useful for tracing the full journey of one message.
     *
     * @param originalMessageId  the JMS MessageId from the source queue
     */
    List<AuditDocument> findByOriginalMessageId(String originalMessageId);

    /**
     * Find all audit records for a specific scenario.
     * e.g. findByRoutingSlipScenarioName("Scenario1")
     */
    List<AuditDocument> findByRoutingSlip_ScenarioName(String scenarioName);

    /**
     * Find all audit records for a specific route leg.
     * e.g. findByRouteInfoRouteName("Route1")
     */
    List<AuditDocument> findByRouteInfo_RouteName(String routeName);

    /**
     * Find all audit records for a specific scenario + route combination.
     * e.g. all Route2 records for Scenario1
     */
    List<AuditDocument> findByRoutingSlip_ScenarioNameAndRouteInfo_RouteName(
            String scenarioName, String routeName);
}