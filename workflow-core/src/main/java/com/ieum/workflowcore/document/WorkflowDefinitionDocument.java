package com.ieum.workflowcore.document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document storing workflow node and edge definitions.
 * The ObjectId string is referenced from WorkflowVersion.mongoDefinitionId in PostgreSQL.
 */
@Document(collection = "workflow_definitions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WorkflowDefinitionDocument {

    @Id
    private String id;  // MongoDB ObjectId (24-char hex string)

    @Indexed
    private String workflowVersionId;  // back-reference to WorkflowVersion UUID

    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;

    private LocalDateTime createdAt;
}
