package com.ieum.api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.jackson.JsonComponent;

/**
 * 응답의 LocalDateTime을 Instant 문자열("…Z")로 쓴다. 저장값은 JVM 기본 시간대(dev = UTC) 기준 벽시계라
 * 시간대 없이 내보내면 브라우저가 현지 시각으로 읽어 9시간 틀린다. 저장값·JVM 시간대는 바꾸지 않는다.
 */
@JsonComponent
public class LocalDateTimeUtcSerializer extends JsonSerializer<LocalDateTime> {

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(value.atZone(ZoneId.systemDefault()).toInstant().toString());
    }
}
