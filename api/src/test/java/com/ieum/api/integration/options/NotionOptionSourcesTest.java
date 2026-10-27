package com.ieum.api.integration.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.params.provider.ValueSource;
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

    private static final String API = "https://api.notion.com/v1";
    private static final String SEARCH_URL = API + "/search";
    private static final String DS = "1f2e3d4c5b6a79881f2e3d4c5b6a7988";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final NotionTokenProvider tokenProvider = mock(NotionTokenProvider.class);
    private final NotionApiReader reader = new NotionApiReader(tokenProvider, restTemplate);
    private final NotionPagesOptionSource pages = new NotionPagesOptionSource(reader);
    private final NotionDatabasesOptionSource databases = new NotionDatabasesOptionSource(reader);
    private final NotionPropertiesFieldSource properties = new NotionPropertiesFieldSource(reader);
    private final NotionDataSourceItemsOptionSource items = new NotionDataSourceItemsOptionSource(reader);
    private final NotionTemplatesOptionSource templates = new NotionTemplatesOptionSource(reader);
    private final NotionUsersOptionSource users = new NotionUsersOptionSource(reader);
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
    @DisplayName("403(연동 권한 부족)은 재연동을 안내한다")
    void forbiddenSuggestsReconnect() {
        connected();
        server.expect(requestTo(SEARCH_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> reader.search(userId, "page", null))
            .hasMessage("Notion 연동 권한이 부족합니다. Notion을 다시 연동해 주세요.");
    }

    @Test
    @DisplayName("Notion 타임아웃은 NOTION_API_UNAVAILABLE")
    void timeoutMapsToUnavailable() {
        connected();
        server.expect(requestTo(SEARCH_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertErrorCode(() -> reader.search(userId, "page", null), ErrorCode.NOTION_API_UNAVAILABLE);
    }

    @Test
    @DisplayName("dataSource — GET /v1/data_sources/{id}, 2026-03-11 헤더, 본문 없음")
    void dataSourceGetsSchema() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer ntn-token"))
            .andExpect(header("Notion-Version", "2026-03-11"))
            .andExpect(content().string(""))
            .andRespond(withSuccess("{\"object\":\"data_source\",\"properties\":{}}", MediaType.APPLICATION_JSON));

        assertThat(reader.dataSource(userId, DS).path("object").asText()).isEqualTo("data_source");
    }

    @Test
    @DisplayName("queryDataSource — title만, 최근 편집순 sorts(배열 — search의 sort 아님), 행만, 50개. q·cursor 없으면 filter·start_cursor 없음")
    void queryDataSourceFirstPage() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/query?filter_properties=title"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Notion-Version", "2026-03-11"))
            .andExpect(jsonPath("$.sorts[0].timestamp").value("last_edited_time"))
            .andExpect(jsonPath("$.sorts[0].direction").value("descending"))
            .andExpect(jsonPath("$.sort").doesNotExist())
            .andExpect(jsonPath("$.result_type").value("page"))
            .andExpect(jsonPath("$.page_size").value(50))
            .andExpect(jsonPath("$.filter").doesNotExist())
            .andExpect(jsonPath("$.start_cursor").doesNotExist())
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        reader.queryDataSource(userId, DS, null, null);
    }

    @Test
    @DisplayName("queryDataSource — q는 title contains 필터, cursor는 start_cursor")
    void queryDataSourceWithQueryAndCursor() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/query?filter_properties=title"))
            .andExpect(jsonPath("$.filter.property").value("title"))
            .andExpect(jsonPath("$.filter.title.contains").value("회의"))
            .andExpect(jsonPath("$.start_cursor").value("c2"))
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        reader.queryDataSource(userId, DS, "회의", "c2");
    }

    @Test
    @DisplayName("templates — 100개씩, q는 name 쿼리(& 까지 인코딩), cursor는 start_cursor")
    void templatesQuery() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"templates\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100&name=a%26b&start_cursor=c2"))
            .andRespond(withSuccess("{\"templates\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        reader.templates(userId, DS, null, null);
        reader.templates(userId, DS, "a&b", "c2");
    }

    @Test
    @DisplayName("users — GET /v1/users 100명씩, cursor는 start_cursor")
    void usersQuery() {
        connected();
        server.expect(requestTo(API + "/users?page_size=100"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Notion-Version", "2026-03-11"))
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/users?page_size=100&start_cursor=u2"))
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        reader.users(userId, null);
        reader.users(userId, "u2");
    }

    @ParameterizedTest
    @CsvSource({
        "401, AUTHENTICATION_REQUIRED",
        "403, INVALID_INPUT",
        "404, INVALID_INPUT",
        "429, NOTION_API_UNAVAILABLE",
        "500, NOTION_API_UNAVAILABLE"
    })
    @DisplayName("새 호출도 search와 같은 오류 매핑 — 404(공유되지 않은 DB)는 INVALID_INPUT")
    void newCallsShareErrorMapping(int status, ErrorCode expected) {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS)).andRespond(withStatus(HttpStatus.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"object\":\"error\",\"status\":" + status + ",\"code\":\"x\",\"message\":\"id abc\"}"));

        assertErrorCode(() -> reader.dataSource(userId, DS), expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{{nodes.a.output.id}}", "../users", "1f2e3d4c5b6a79881f2e3d4c5b6a7988?x=1",
        "1f2e3d4c5b6a79881f2e3d4c5b6a798", "1f2e3d4c5b6a79881f2e3d4c5b6a7988/query", "1f2e3d4c-5b6a-7988-1f2e-3d4c5b6a798g"})
    @DisplayName("data_source_id가 비었거나 참조식이거나 Notion id 형식이 아니면 INVALID_INPUT — 경로에 끼우지 않는다")
    void dataSourceIdRejectsBadInput(String raw) {
        assertErrorCode(() -> NotionApiReader.dataSourceId(Map.of("data_source_id", raw)), ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("data_source_id — 빈 값·참조식은 고칠 길을 알려 주고, 하이픈 있는 UUID·앞뒤 공백은 통과")
    void dataSourceIdMessagesAndAccepts() {
        assertThatThrownBy(() -> NotionApiReader.dataSourceId(Map.of()))
            .hasMessage("데이터베이스를 먼저 골라 주세요.");
        assertThatThrownBy(() -> NotionApiReader.dataSourceId(Map.of("data_source_id", "{{nodes.a.output.id}}")))
            .hasMessage("변수 참조로 지정된 데이터베이스는 실행 전이라 불러올 수 없어요.");
        assertThat(NotionApiReader.dataSourceId(Map.of("data_source_id", " 1f2e3d4c-5b6a-7988-1f2e-3d4c5b6a7988 ")))
            .isEqualTo("1f2e3d4c-5b6a-7988-1f2e-3d4c5b6a7988");
        assertThat(NotionApiReader.dataSourceId(Map.of("data_source_id", DS))).isEqualTo(DS);
    }

    @Test
    @DisplayName("검색어 q — 앞뒤 공백을 떼고 비면 null")
    void queryStripsAndBlankIsNull() {
        assertThat(NotionApiReader.query(Map.of("q", "  회의 "))).isEqualTo("회의");
        assertThat(NotionApiReader.query(Map.of("q", "  "))).isNull();
        assertThat(NotionApiReader.query(Map.of())).isNull();
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
    @DisplayName("notion.properties — 지원 타입 전부를 spec §4-3대로, title 맨 앞·나머지 이름순, 전부 required false")
    void propertiesMapEveryNotionType() throws Exception {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS))
            .andRespond(withSuccess("""
                {"object":"data_source","id":"%s","properties":{
                  "점수":{"id":"n%%3F1","name":"점수","type":"number","number":{"format":"number"}},
                  "이름":{"id":"title","name":"이름","type":"title","title":{}},
                  "상태":{"id":"a%%3Bc","name":"상태","type":"status","description":"진행 단계",
                          "status":{"options":[{"id":"opt1","name":"진행 중","color":"blue"},{"id":"opt2","name":"완료","color":"green"}],
                                    "groups":[]}},
                  "담당":{"id":"Xy.z","name":"담당","type":"people","people":{}},
                  "프로젝트":{"id":"rel1","name":"프로젝트","type":"relation",
                            "relation":{"database_id":"db-9","data_source_id":"ds-9","type":"single_property","single_property":{}}},
                  "마감":{"id":"due","name":"마감","type":"date","date":{}},
                  "메모":{"id":"memo","name":"메모","type":"rich_text","rich_text":{},"description":null},
                  "완료":{"id":"done","name":"완료","type":"checkbox","checkbox":{}},
                  "단계":{"id":"sel","name":"단계","type":"select","select":{"options":[{"id":"s1","name":"기획"}]}},
                  "태그":{"id":"tags","name":"태그","type":"multi_select","multi_select":{"options":[{"id":"t1","name":"긴급"}]}},
                  "링크":{"id":"link","name":"링크","type":"url","url":{}},
                  "메일":{"id":"mail","name":"메일","type":"email","email":{}},
                  "전화":{"id":"tel","name":"전화","type":"phone_number","phone_number":{}}}}
                """.formatted(DS), MediaType.APPLICATION_JSON));

        JsonNode fields = JSON.valueToTree(properties.fetch(userId, Map.of("data_source_id", DS)));

        assertThat(fields).isEqualTo(json("""
            [{"key":"title","title":"이름","type":"string","required":false},
             {"key":"sel","title":"단계","type":"string","required":false,"choices":[{"id":"s1","name":"기획"}]},
             {"key":"Xy%2Ez","title":"담당","type":"string","required":false,"list":true,"optionsSource":"notion.users"},
             {"key":"link","title":"링크","type":"string","required":false},
             {"key":"due","title":"마감","type":"object","required":false,"children":[
               {"key":"start","title":"시작","type":"datetime","required":true},
               {"key":"end","title":"끝","type":"datetime","required":false}]},
             {"key":"memo","title":"메모","type":"text","required":false},
             {"key":"mail","title":"메일","type":"string","required":false},
             {"key":"a%3Bc","title":"상태","type":"string","required":false,"description":"진행 단계",
              "choices":[{"id":"opt1","name":"진행 중"},{"id":"opt2","name":"완료"}]},
             {"key":"done","title":"완료","type":"boolean","required":false},
             {"key":"tel","title":"전화","type":"string","required":false},
             {"key":"n%3F1","title":"점수","type":"number","required":false},
             {"key":"tags","title":"태그","type":"string","required":false,"list":true,"choices":[{"id":"t1","name":"긴급"}]},
             {"key":"rel1","title":"프로젝트","type":"string","required":false,"list":true,
              "optionsSource":"notion.data_source_items","optionsParams":{"data_source_id":"ds-9"}}]
            """));
    }

    @Test
    @DisplayName("notion.properties — 쓸 수 없는 타입(읽기 전용·파일·장소·검증·처음 보는 타입)은 칸이 없다")
    void unsupportedTypesHaveNoField() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS))
            .andRespond(withSuccess("""
                {"properties":{
                  "Name":{"id":"title","name":"Name","type":"title","title":{}},
                  "f":{"id":"f1","name":"f","type":"formula","formula":{"expression":"1"}},
                  "r":{"id":"r1","name":"r","type":"rollup","rollup":{}},
                  "ct":{"id":"c1","name":"ct","type":"created_time","created_time":{}},
                  "cb":{"id":"c2","name":"cb","type":"created_by","created_by":{}},
                  "et":{"id":"e1","name":"et","type":"last_edited_time","last_edited_time":{}},
                  "eb":{"id":"e2","name":"eb","type":"last_edited_by","last_edited_by":{}},
                  "uid":{"id":"u1","name":"uid","type":"unique_id","unique_id":{"prefix":"T"}},
                  "btn":{"id":"b1","name":"btn","type":"button","button":{}},
                  "file":{"id":"fi","name":"file","type":"files","files":{}},
                  "place":{"id":"pl","name":"place","type":"place","place":{}},
                  "ver":{"id":"v1","name":"ver","type":"verification","verification":{}},
                  "new":{"id":"nw","name":"new","type":"something_new","something_new":{}}}}
                """, MediaType.APPLICATION_JSON));

        assertThat(properties.fetch(userId, Map.of("data_source_id", DS)))
            .extracting(field -> field.get("key")).containsExactly("title");
    }

    @Test
    @DisplayName("칸 key는 Notion 속성 ID에서 '.'만 %2E — 이미 인코딩된 %xx는 다시 인코딩하지 않는다")
    void keyEncodesDotOnly() {
        assertThat(NotionPropertiesFieldSource.fieldKey("Xy.z")).isEqualTo("Xy%2Ez");
        assertThat(NotionPropertiesFieldSource.fieldKey("a%3Bc")).isEqualTo("a%3Bc");
        assertThat(NotionPropertiesFieldSource.fieldKey("f%5C%5C%3Ap.")).isEqualTo("f%5C%5C%3Ap%2E");
        assertThat(NotionPropertiesFieldSource.fieldKey("title")).isEqualTo("title");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{{nodes.a.output.id}}", "../users"})
    @DisplayName("notion.properties — data_source_id가 비었거나 참조식·형식 오류면 토큰 조회·HTTP 없이 INVALID_INPUT")
    void propertiesRejectBadDataSourceIdBeforeTokenLookup(String raw) {
        assertErrorCode(() -> properties.fetch(userId, Map.of("data_source_id", raw)), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    @DisplayName("notion.data_source_items — 행만(data_source 결과는 건너뜀), 값은 페이지 ID·이름은 title(없으면 (제목 없음)), 다음 커서")
    void itemsQueryUsesSortsAndSkipsDataSources() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/query?filter_properties=title"))
            .andExpect(jsonPath("$.sorts[0].timestamp").value("last_edited_time"))
            .andExpect(jsonPath("$.sort").doesNotExist())
            .andExpect(jsonPath("$.result_type").value("page"))
            .andRespond(withSuccess("""
                {"object":"list","type":"page_or_data_source","has_more":true,"next_cursor":"r-2","results":[
                  {"object":"page","id":"row-1","properties":{"이름":{"id":"title","type":"title","title":[{"plain_text":"10월 회의"}]}}},
                  {"object":"data_source","id":"ds-wiki","properties":{}},
                  {"object":"page","id":"row-2","properties":{"Name":{"id":"title","type":"title","title":[]}}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = items.fetch(userId, Map.of("data_source_id", DS), null);

        assertThat(page.items()).containsExactly(new OptionItem("row-1", "10월 회의"), new OptionItem("row-2", "(제목 없음)"));
        assertThat(page.nextCursor()).isEqualTo("r-2");
    }

    @Test
    @DisplayName("notion.data_source_items — q(앞뒤 공백 제거)는 title contains 필터, cursor는 start_cursor")
    void itemsSearchByTitle() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/query?filter_properties=title"))
            .andExpect(jsonPath("$.filter.title.contains").value("회의"))
            .andExpect(jsonPath("$.start_cursor").value("r-2"))
            .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        assertThat(items.fetch(userId, Map.of("data_source_id", DS, "q", " 회의 "), "r-2").items()).isEmpty();
    }

    @Test
    @DisplayName("notion.templates — 검색어 없는 첫 페이지만 맨 앞에 default, q가 있거나 다음 페이지면 없다")
    void templatesDefaultOnlyOnFirstPageWithoutQuery() {
        connected();
        String body = """
            {"templates":[{"id":"tpl-1","name":"회의록","is_default":false}],"has_more":true,"next_cursor":"t-2"}""";
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100&name=%ED%9A%8C%EC%9D%98"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100&start_cursor=t-2"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        OptionPage first = templates.fetch(userId, Map.of("data_source_id", DS), null);
        OptionPage searched = templates.fetch(userId, Map.of("data_source_id", DS, "q", "회의"), null);
        OptionPage next = templates.fetch(userId, Map.of("data_source_id", DS, "q", "  "), "t-2");

        assertThat(first.items()).containsExactly(new OptionItem("default", "기본 템플릿"), new OptionItem("tpl-1", "회의록"));
        assertThat(first.nextCursor()).isEqualTo("t-2");
        assertThat(searched.items()).containsExactly(new OptionItem("tpl-1", "회의록"));
        assertThat(next.items()).containsExactly(new OptionItem("tpl-1", "회의록"));
    }

    @Test
    @DisplayName("notion.templates — 이름이 빈 문자열·공백뿐이면 (제목 없음)")
    void templatesBlankNameIsUntitled() {
        connected();
        server.expect(requestTo(API + "/data_sources/" + DS + "/templates?page_size=100&name=x"))
            .andRespond(withSuccess("""
                {"templates":[{"id":"tpl-1","name":""},{"id":"tpl-2","name":"  "}],"has_more":false}""",
                MediaType.APPLICATION_JSON));

        OptionPage page = templates.fetch(userId, Map.of("data_source_id", DS, "q", "x"), null);

        assertThat(page.items()).containsExactly(new OptionItem("tpl-1", "(제목 없음)"), new OptionItem("tpl-2", "(제목 없음)"));
    }

    @Test
    @DisplayName("notion.users — 사람만(봇 제외), 값은 사용자 ID, q는 보내지 않는다")
    void usersArePeopleOnly() {
        connected();
        server.expect(requestTo(API + "/users?page_size=100"))
            .andRespond(withSuccess("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"object":"user","id":"u-1","type":"person","name":"윤도훈","person":{"email":"a@b.c"}},
                  {"object":"user","id":"b-1","type":"bot","name":"IEUM","bot":{}},
                  {"object":"user","id":"u-2","type":"person","name":null,"person":{}},
                  {"object":"user","id":"u-3","type":"person","name":" ","person":{}}]}
                """, MediaType.APPLICATION_JSON));

        OptionPage page = users.fetch(userId, Map.of("q", "윤"), null);

        assertThat(page.items()).containsExactly(new OptionItem("u-1", "윤도훈"), new OptionItem("u-2", "u-2"),
            new OptionItem("u-3", "u-3"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("notion.users — 연결에 사용자 정보 권한이 없으면(403) 재연동 안내 INVALID_INPUT")
    void usersWithoutCapabilityIsInvalidInput() {
        connected();
        server.expect(requestTo(API + "/users?page_size=100")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> users.fetch(userId, Map.of(), null))
            .hasMessage("Notion 연동 권한이 부족합니다. Notion을 다시 연동해 주세요.");
    }

    @Test
    @DisplayName("행·템플릿 드롭다운 — data_source_id가 참조식·형식 오류면 토큰 조회·HTTP 없이 INVALID_INPUT")
    void itemsAndTemplatesRejectBadDataSourceId() {
        assertErrorCode(() -> items.fetch(userId, Map.of("data_source_id", "{{nodes.a.output.id}}"), null),
            ErrorCode.INVALID_INPUT);
        assertErrorCode(() -> templates.fetch(userId, Map.of("data_source_id", "../users"), null),
            ErrorCode.INVALID_INPUT);
        assertErrorCode(() -> items.fetch(userId, Map.of(), null), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    @DisplayName("공급원 키는 agent FIELD_META의 optionsSource와 1:1 계약")
    void keysMatchAgentOptionsSource() {
        assertThat(List.of(pages.key(), databases.key(), items.key(), templates.key(), users.key()))
            .containsExactly("notion.pages", "notion.databases", "notion.data_source_items", "notion.templates", "notion.users");
        assertThat(properties.key()).isEqualTo("notion.properties");
    }
}
