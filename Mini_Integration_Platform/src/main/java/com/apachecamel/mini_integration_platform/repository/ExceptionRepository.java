package com.apachecamel.mini_integration_platform.repository;

import com.apachecamel.mini_integration_platform.model.document.ExceptionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ExceptionRepository
 *
 * Spring Data MongoDB repository for the "exceptions" collection.
 * Provides CRUD operations and custom finder methods.
 *
 * Collection : exceptions
 * Document   : ExceptionDocument
 * Key type   : String (MongoDB ObjectId as String)
 */
@Repository
public interface ExceptionRepository extends MongoRepository<ExceptionDocument, String> {

    /**
     * Find all exception records for a given original message.
     * Allows correlating an exception with its audit trail.
     *
     * @param originalMessageId  the JMS MessageId from the source queue
     */
    List<ExceptionDocument> findByOriginalMessageId(String originalMessageId);

    /**
     * Find all exceptions with a specific exception code.
     * e.g. findByExceptionCode("ExceptionCode1")  → all JSON parse errors
     */
    List<ExceptionDocument> findByExceptionCode(String exceptionCode);

    /**
     * Find all exceptions for a specific scenario.
     * e.g. findByRoutingSlipScenarioName("Scenario1")
     */
    List<ExceptionDocument> findByRoutingSlip_ScenarioName(String scenarioName);

    /**
     * Find all exceptions that occurred in a specific route.
     * e.g. findByRouteInfoRouteName("Route2")
     */
    List<ExceptionDocument> findByRouteInfo_RouteName(String routeName);

    /**
     * Find exceptions by scenario name and exception code.
     * Useful for targeted error analysis per scenario.
     */
    List<ExceptionDocument> findByRoutingSlip_ScenarioNameAndExceptionCode(
            String scenarioName, String exceptionCode);
}