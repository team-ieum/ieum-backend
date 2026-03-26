package com.ieum.ai.prompt.repository;

import static com.ieum.ai.prompt.domain.QPromptTemplate.promptTemplate;

import com.ieum.ai.prompt.domain.PromptTemplate;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PromptTemplateQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<PromptTemplate> findByUserId(UUID userId, String category, String search, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(promptTemplate.userId.eq(userId));
        if (category != null && !category.isBlank()) {
            where.and(promptTemplate.category.eq(category));
        }
        if (search != null && !search.isBlank()) {
            where.and(
                promptTemplate.name.containsIgnoreCase(search)
                    .or(promptTemplate.description.containsIgnoreCase(search))
            );
        }

        List<PromptTemplate> content = queryFactory
            .selectFrom(promptTemplate)
            .where(where)
            .orderBy(promptTemplate.updatedAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

        Long total = queryFactory
            .select(promptTemplate.count())
            .from(promptTemplate)
            .where(where)
            .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
