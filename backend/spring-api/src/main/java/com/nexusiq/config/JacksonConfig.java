package com.nexusiq.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot 4.1 ships a new Jackson 3 fork (tools.jackson.*) as its default JSON
 * engine — jackson-annotations (com.fasterxml.jackson.annotation) is the one
 * module Jackson 3 still shares with Jackson 2, which is why JsonInclude comes
 * from the classic package below while everything else is tools.jackson.
 *
 * The classic spring.jackson.property-naming-strategy /
 * default-property-inclusion YAML properties had no effect on this mapper when
 * tried (confirmed empirically against a running app — response stayed camelCase
 * and null fields were not omitted). JsonMapperBuilderCustomizer is the
 * supported programmatic hook, so global snake_case
 * (.claude/rules/backend-java.md, docs/API/API_DESIGN.md) is set here instead.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer nexusIqJsonMapperBuilderCustomizer() {
        // Jackson 3 already defaults java.time types to ISO-8601 (the old
        // WRITE_DATES_AS_TIMESTAMPS feature was removed), so only naming,
        // inclusion and unknown-property leniency need setting here.
        return (JsonMapper.Builder builder) -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.ALL_NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
