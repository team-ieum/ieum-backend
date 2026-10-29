package com.ieum.api.config;

import com.ieum.workflowcore.engine.executor.HttpNodeExecutor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class RestTemplateConfig {

    /** 공용 클라이언트 — 외부 API(OAuth·Notion·GitHub·Google 등) 호출. 이름 없이 주입하면 이것이다. */
    @Bean
    @Primary
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${ieum.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${ieum.http.read-timeout-seconds:30}") int readTimeoutSeconds
    ) {
        return builder
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
    }

    /**
     * HTTP 노드 전용 (IEUM-BE-106). 사용자가 적은 주소를 부르므로 SSRF 경계다.
     * <ul>
     *   <li>리다이렉트를 따라가지 않는다 — {@code HttpNodeExecutor.validateUrl}은 첫 호스트만 보므로, 따라가면 302 한 번으로
     *       내부망·클라우드 메타데이터에 닿고 그 본문이 테스트 결과로 돌아온다. 3xx는 그대로 노드 결과다.</li>
     *   <li>응답 본문은 {@link HttpNodeExecutor#MAX_RESPONSE_BYTES}까지만 읽는다(오류 응답 본문 포함).</li>
     * </ul>
     */
    @Bean
    public RestTemplate httpNodeRestTemplate(
            RestTemplateBuilder builder,
            @Value("${ieum.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${ieum.http.read-timeout-seconds:30}") int readTimeoutSeconds
    ) {
        return builder
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .redirects(Redirects.DONT_FOLLOW)
                .additionalInterceptors(capResponseBody(HttpNodeExecutor.MAX_RESPONSE_BYTES))
                .build();
    }

    /** 응답 본문을 {@code maxBytes}까지만 읽어 넘기고 나머지는 버린다(연결은 닫는다). */
    static ClientHttpRequestInterceptor capResponseBody(int maxBytes) {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] bytes;
            try (InputStream in = response.getBody()) {
                bytes = in.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                log.warn("[HttpNode] 응답 본문이 {}바이트를 넘어 잘라 냈습니다 — host: {}", maxBytes, request.getURI().getHost());
                bytes = Arrays.copyOf(bytes, maxBytes);
            }
            return new CappedResponse(response, bytes);
        };
    }

    private record CappedResponse(ClientHttpResponse delegate, byte[] body) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
