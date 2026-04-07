package com.ieum.api.prompt.domain;

import com.ieum.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "prompt_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PromptTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String category;

    @Column(name = "system_message", nullable = false, columnDefinition = "TEXT")
    private String systemMessage;

    @Column(name = "user_message_template", nullable = false, columnDefinition = "TEXT")
    private String userMessageTemplate;

    @Column(name = "input_variables", nullable = false, columnDefinition = "jsonb")
    private String inputVariables;

    @Column(name = "default_parameters", columnDefinition = "jsonb")
    private String defaultParameters;

    @Column(nullable = false)
    private int version;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    public void update(String name, String description, String category,
            String systemMessage, String userMessageTemplate,
            String inputVariables, String defaultParameters) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.systemMessage = systemMessage;
        this.userMessageTemplate = userMessageTemplate;
        this.inputVariables = inputVariables;
        this.defaultParameters = defaultParameters;
        this.version++;
    }

    public void incrementVersion() {
        this.version++;
    }
}
