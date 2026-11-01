package com.ieum.workflowcore.catalog;

import java.util.Set;

/** presets.json 항목. AI 생성 프리셋이며 이벤트가 아니다. 노드 event는 ai.agent다. */
public record PresetDefinition(String id, Set<String> optionsSources, Set<String> fieldsSources) {
}
