package com.ieum.api.integration.options;

import com.ieum.api.integration.options.OptionPage.OptionItem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 찾을 열·보조 열 드롭다운. 값은 열 문자 */
@Component
@RequiredArgsConstructor
public class GoogleSheetColumnsOptionSource implements OptionSource {

    private final GoogleSheetColumnsReader columnsReader;

    @Override
    public String key() {
        return "google.sheet_columns";
    }

    @Override
    public OptionPage fetch(UUID userId, Map<String, String> inputs, String cursor) {
        List<OptionItem> items = columnsReader.columns(userId, inputs).stream()
            .map(c -> new OptionItem(c.letter(), c.header() == null ? c.letter() : c.letter() + " · " + c.header()))
            .toList();
        return new OptionPage(items, null);
    }
}
