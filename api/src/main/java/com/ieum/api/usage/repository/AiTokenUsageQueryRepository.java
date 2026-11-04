package com.ieum.api.usage.repository;

import static com.ieum.api.usage.domain.QAiTokenUsage.aiTokenUsage;

import com.ieum.api.credential.domain.AiProvider;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.DateExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 사용량 집계. 기간은 {@code [fromInclusive, toExclusive)}이고 날짜 기준은 {@code created_at}과 같은
 * 서버 시간대다. 정렬은 고정 필드만 쓴다(사용자 입력 없음).
 */
@Repository
@RequiredArgsConstructor
public class AiTokenUsageQueryRepository {

    private final JPAQueryFactory queryFactory;

    /** 집계 한 줄. date는 provider·모델 합계에선 null. 토큰 합은 보고된 행만 더하고, 하나도 없으면 0. */
    public record UsageSum(LocalDate date, AiProvider provider, String model,
                           long inputTokens, long outputTokens, long totalTokens, long callCount) {}

    public List<UsageSum> sumByProviderAndModel(UUID userId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return queryFactory
            .select(aiTokenUsage.provider, aiTokenUsage.model,
                aiTokenUsage.inputTokens.sumLong(), aiTokenUsage.outputTokens.sumLong(),
                aiTokenUsage.totalTokens.sumLong(), aiTokenUsage.count())
            .from(aiTokenUsage)
            .where(inRange(userId, fromInclusive, toExclusive))
            .groupBy(aiTokenUsage.provider, aiTokenUsage.model)
            .fetch()
            .stream()
            .map(t -> toSum(t, null))
            .toList();
    }

    public List<UsageSum> sumByDateProviderAndModel(UUID userId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        DateExpression<LocalDate> date =
            Expressions.dateTemplate(LocalDate.class, "cast({0} as date)", aiTokenUsage.createdAt);
        return queryFactory
            .select(aiTokenUsage.provider, aiTokenUsage.model,
                aiTokenUsage.inputTokens.sumLong(), aiTokenUsage.outputTokens.sumLong(),
                aiTokenUsage.totalTokens.sumLong(), aiTokenUsage.count(), date)
            .from(aiTokenUsage)
            .where(inRange(userId, fromInclusive, toExclusive))
            .groupBy(date, aiTokenUsage.provider, aiTokenUsage.model)
            .orderBy(date.asc(), aiTokenUsage.provider.asc(), aiTokenUsage.model.asc())
            .fetch()
            .stream()
            .map(t -> toSum(t, toLocalDate(t.get(6, Object.class))))
            .toList();
    }

    private static Predicate[] inRange(UUID userId, LocalDateTime from, LocalDateTime to) {
        return new Predicate[] {
            aiTokenUsage.userId.eq(userId),
            aiTokenUsage.createdAt.goe(from),
            aiTokenUsage.createdAt.lt(to)
        };
    }

    private static UsageSum toSum(Tuple t, LocalDate date) {
        return new UsageSum(date, t.get(0, AiProvider.class), t.get(1, String.class),
            asLong(t.get(2, Object.class)), asLong(t.get(3, Object.class)),
            asLong(t.get(4, Object.class)), asLong(t.get(5, Object.class)));
    }

    // 보고된 토큰이 하나도 없으면 sum이 null이다 — 0으로 본다
    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    // cast(... as date)의 결과 타입이 방언에 따라 LocalDate·java.sql.Date로 갈린다
    private static LocalDate toLocalDate(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }
}
