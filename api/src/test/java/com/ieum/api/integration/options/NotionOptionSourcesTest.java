package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieum.api.integration.options.OptionPage.OptionItem;
import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import com.ieum.workflowcore.engine.executor.NotionTokenProvider;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 드롭다운 Notion 공급원의 search 호출 모양·이름 추출·오류 매핑을 고정한다 (IEUM-BE-73).
 *
 * <p>요청 없이 끝나야 하는 케이스는 기대 요청을 등록하지 않는다 — 요청이 나가면 테스트가 깨진다.
 */
class NotionOptionSourcesTest {

    private static final String SEARCH_URL = "https://api.notion.com/v1/search";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final NotionTokenProvider tokenProvider = mock(NotionTokenProvider.class);
    private final NotionApiReader reader = new NotionApiReader(tokenProvider, restTemplate);
    private final NotionPagesOptionSource pages = new NotionPagesOptionSource(reader);
    private final NotionDatabasesOptionSource databases = new NotionDatabasesOptionSource(reader);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    private void connected() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.of("ntn-token"));
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOf(CustomException.class)
            .extracting(e -> ((CustomException) e).getErrorCode())
            .isEqualTo(expected);
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    @Test
    @DisplayName("search — 2026-03-11 헤더, object 필터, 최근 편집순 50개, 첫 페이지면 start_cursor 없음")
    void searchSendsVersionAndBody() {
        connected();
        server.expect(requestTo(SEARCH_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer ntn-token"))
            .andExpect(header("Notion-Version", "2026-03-11"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.filter.property").value("object"))
            .andExpect(jsonPath("$.filter.value").value("page"))
            .andExpect(jsonPath("$.sort.timestamp").value("last_edited_time"))
            .andExpect(jsonPath("$.sort.direction").value("descending"))
            .andExpect(jsonPath("$.page_size").value(50))
            .andExpect(jsonPath("$.start_cursor").doesNotExist())
            .andExpect(jsonPath("$.query").doesNotExist())
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        assertThat(reader.search(userId, "page", null).path("results").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("search — cursor는 start_cursor로 나간다")
    void searchPassesCursor() {
        connected();
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.filter.value").value("data_source"))
            .andExpect(jsonPath("$.start_cursor").value("cur-2"))
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        reader.search(userId, "data_source", "cur-2");
    }

    @Test
    @DisplayName("Notion 토큰이 없으면(연동 없음·복호화 실패) HTTP 호출 없이 ACCOUNT_NOT_CONNECTED")
    void noTokenMeansNotConnected() {
        given(tokenProvider.getAccessToken(userId)).willReturn(Optional.empty());

        assertErrorCode(() -> reader.search(userId, "page", null), ErrorCode.ACCOUNT_NOT_CONNECTED);
    }

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_INPUT",
        "401, AUTHENTICATION_REQUIRED",
        "403, INVALID_INPUT",
        "429, NOTION_API_UNAVAILABLE",
        "500, NOTION_API_UNAVAILABLE",
        "503, NOTION_API_UNAVAILABLE"
    })
    @DisplayName("Notion 응답 상태를 ErrorCode로 옮긴다")
    void notionErrorMapsToErrorCode(int status, ErrorCode expected) {
        connected();
        server.expect(requestTo(SEARCH_URL)).andRespond(withStatus(HttpStatus.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"object\":\"error\",\"status\":" + status + ",\"code\":\"x\",\"message\":\"id abc\"}"));

        assertErrorCode(() -> reader.search(userId, "page", null), expected);
    }

    @Test
    @DisplayName("401은 Google이 아니라 Notion 재연동 문구로 나간다")
    void unauthorizedSaysNotion() {
        connected();
        server.expect(requestTo(SEARCH_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> reader.search(userId, "page", null))
            .hasMessage("Notion 계정을 다시 연동해주세요.");
    }

    @Test
    @DisplayName("Notion 타임아웃은 NOTION_API_UNAVAILABLE")
    void timeoutMapsToUnavailable() {
        connected();
        server.expect(requestTo(SEARCH_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> reader.search(userId, "page", null), ErrorCode.NOTION_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("다음 커서는 has_more가 true일 때만 — false면 next_cursor 값이 있어도 null")
    void nextCursorOnlyWhenHasMore() throws Exception {
        assertThat(NotionApiReader.nextCursor(json("{\"has_more\":true,\"next_cursor\":\"c2\"}"))).isEqualTo("c2");
        assertThat(NotionApiReader.nextCursor(json("{\"has_more\":false,\"next_cursor\":\"c2\"}"))).isNull();
        assertThat(NotionApiReader.nextCursor(json("{\"has_more\":true,\"next_cursor\":null}"))).isNull();
    }

    @Test
    @DisplayName("rich text는 plain_text를 잇고, 비거나 공백이면 (제목 없음)")
    void plainTextOrUntitled() throws Exception {
        assertThat(NotionApiReader.plainTextOrUntitled(json("[{\"plain_text\":\"회의\"},{\"plain_text\":\"록\"}]")))
            .isEqualTo("회의록");
        assertThat(NotionApiReader.plainTextOrUntitled(json("[]"))).isEqualTo("(제목 없음)");
        assertThat(NotionApiReader.plainTextOrUntitled(json("[{\"plain_text\":\"  \"}]"))).isEqualTo("(제목 없음)");
    }

    @Test
    @DisplayName("페이지 목록 — title 속성은 이름이 아니라 type으로 찾는다(이름·Name·제목), 제목 없으면 (제목 없음)")
    void pagesTakeTitleByType() {
        connected();
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.filter.value").value("page"))
            .andRespond(withSuccess("""
                {"has_more":true,"next_cursor":"p-2","results":[
                  {"object":"page","id":"p1","properties":{
                     "상태":{"type":"status","status":{"name":"진행중"}},
                     "이름":{"type":"title","title":[{"plain_text":"주간 "},{"plain_text":"회의록"}]}}},
                  {"object":"page","id":"p2","properties":{"Name":{"type":"title","title":[]}}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = pages.fetch(userId, Map.of(), null);

        assertThat(page.items()).containsExactly(
            new OptionItem("p1", "주간 회의록"),
            new OptionItem("p2", "(제목 없음)"));
        assertThat(page.nextCursor()).isEqualTo("p-2");
    }

    @Test
    @DisplayName("데이터베이스 목록 — data source를 검색하고 id는 data source id, 이름은 최상위 title(스키마 properties 아님)")
    void databasesUseTopLevelTitle() {
        connected();
        server.expect(requestTo(SEARCH_URL))
            .andExpect(jsonPath("$.filter.value").value("data_source"))
            .andExpect(jsonPath("$.start_cursor").value("d-1"))
            .andRespond(withSuccess("""
                {"has_more":false,"next_cursor":null,"results":[
                  {"object":"data_source","id":"ds-1","title":[{"plain_text":"업무 보드"}],
                   "properties":{"Name":{"id":"title","name":"Name","type":"title","title":{}}},
                   "parent":{"type":"database_id","database_id":"db-1"}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = databases.fetch(userId, Map.of(), "d-1");

        assertThat(page.items()).containsExactly(new OptionItem("ds-1", "업무 보드"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("공급원 키는 agent FIELD_META의 optionsSource와 1:1 계약")
    void keysMatchAgentOptionsSource() {
        assertThat(List.of(pages.key(), databases.key())).containsExactly("notion.pages", "notion.databases");
    }
}
