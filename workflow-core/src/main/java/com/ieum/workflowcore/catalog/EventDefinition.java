package com.ieum.workflowcore.catalog;

import com.ieum.workflowcore.domain.enums.NodeType;
import com.ieum.workflowcore.domain.enums.TriggerType;
import java.util.Set;

/**
 * 카탈로그 이벤트 하나. 파일은 events/&lt;app&gt;/&lt;event&gt;.json이고 name은 "&lt;app&gt;.&lt;event&gt;"이다.
 *
 * @param triggerType TRIGGER 이벤트만 값이 있다
 * @param toolKey     ACTION은 늘 있고, 그 밖은 없으면 null
 */
public record EventDefinition(String name, String app, NodeType nodeType, String kind, TriggerType triggerType,
                              String toolKey, boolean builder, boolean generation, Set<String> optionsSources) {
}
