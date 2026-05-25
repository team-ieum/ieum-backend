package com.ieum.workflowcore.document;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowDefinitionRepository
        extends MongoRepository<WorkflowDefinitionDocument, String> {
}
