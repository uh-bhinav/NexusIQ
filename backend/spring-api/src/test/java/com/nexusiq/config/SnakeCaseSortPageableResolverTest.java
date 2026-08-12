package com.nexusiq.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class SnakeCaseSortPageableResolverTest {

    private final SnakeCaseSortPageableResolver resolver = new SnakeCaseSortPageableResolver();

    @Test
    void snakeToCamel_convertsUnderscoredProperty() {
        assertThat(resolver.snakeToCamel("created_at")).isEqualTo("createdAt");
        assertThat(resolver.snakeToCamel("occurred_at")).isEqualTo("occurredAt");
        assertThat(resolver.snakeToCamel("total_input_tokens")).isEqualTo("totalInputTokens");
    }

    @Test
    void snakeToCamel_leavesAlreadyCamelOrSingleWordPropertiesUnchanged() {
        assertThat(resolver.snakeToCamel("status")).isEqualTo("status");
        assertThat(resolver.snakeToCamel("createdAt")).isEqualTo("createdAt");
    }

    @Test
    void toCamelCase_convertsEveryOrderInAMultiPropertySort_preservingDirection() {
        Sort sort = Sort.by(Sort.Order.desc("created_at"), Sort.Order.asc("title"));

        Sort converted = resolver.toCamelCase(sort);

        assertThat(converted.getOrderFor("createdAt")).isNotNull();
        assertThat(converted.getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(converted.getOrderFor("title")).isNotNull();
        assertThat(converted.getOrderFor("title").isAscending()).isTrue();
    }

    @Test
    void toCamelCase_leavesAnUnsortedSortAlone() {
        assertThat(resolver.toCamelCase(Sort.unsorted()).isUnsorted()).isTrue();
    }
}
