package com.ieum.api.integration.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Notion DB(data source)의 행 — 최근 편집순 50개씩, 값은 페이지 ID·이름은 제목 (IEUM-BE-105). DB 행 수정 노드의
 * {@code row_id}와 relation 칸({@code /fields}의 optionsParams로 대상 DB id가 온다)이 쓴다. q는 제목 부분 일치.
 */
@Component
@RequiredArgsConstructor
public class NotionDataSourceItemsOptionSource implements OptionSource {

    private final NotionApiReader notionApiReader;

    @Override
    public String key() {
        return "notion.data_source_items";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        String dataSourceId = NotionApiReader.dataSourceId(inputs);
        JsonNode body = notionApiReader.queryDataSource(userId, dataSourceId, NotionApiReader.query(inputs), cursor);

        List<OptionItem> items = new ArrayList<>();
        body.path("results").forEach(result -> {
            // result_type=page를 보내도 wiki DB는 data_source가 섞일 수 있다 — 행이 아니라 건너뛴다
            if ("page".equals(result.path("object").asText())) {
                items.add(new OptionItem(result.path("id").asText(), NotionPagesOptionSource.title(result)));
            }
        });
        return new OptionPage(items, NotionApiReader.nextCursor(body));
    }
}
