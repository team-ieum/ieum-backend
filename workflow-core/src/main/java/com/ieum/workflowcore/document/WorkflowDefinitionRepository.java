package com.ieum.workflowcore.document;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowDefinitionRepository
        extends MongoRepository<WorkflowDefinitionDocument, String> {

    /**
     * Finds the definition document associated with the given WorkflowVersion UUID.
     * Used for reverse lookups from MongoDB back to PostgreSQL.
     */
    Optional<WorkflowDefinitionDocument> findByWorkflowVersionId(String workflowVersionId);
}
