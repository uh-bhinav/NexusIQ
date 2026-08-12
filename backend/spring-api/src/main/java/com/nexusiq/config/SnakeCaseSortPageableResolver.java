package com.nexusiq.config;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The documented pagination convention (.claude/rules/backend-java.md:
 * "?page=0&size=20&sort=created_at,desc") is snake_case, matching every
 * request/response body. Jackson handles that translation for bodies, but
 * Spring Data's own Pageable resolver has no such awareness for sort
 * *properties* — it passes the raw query string straight through to the
 * entity's Java property name, so "created_at" never matches "createdAt"
 * and Spring Data JPA throws PropertyReferenceException the moment the
 * Sort reaches a repository. This resolver wraps the default one and
 * converts each Sort.Order's property from snake_case to camelCase first.
 */
@Component
public class SnakeCaseSortPageableResolver implements HandlerMethodArgumentResolver {

    private final PageableHandlerMethodArgumentResolver delegate = new PageableHandlerMethodArgumentResolver();

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return delegate.supportsParameter(parameter);
    }

    @Override
    public Pageable resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Pageable pageable = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), toCamelCase(pageable.getSort()));
    }

    Sort toCamelCase(Sort sort) {
        if (sort.isUnsorted()) {
            return sort;
        }
        List<Sort.Order> converted =
                sort.stream().map(order -> order.withProperty(snakeToCamel(order.getProperty()))).collect(Collectors.toList());
        return Sort.by(converted);
    }

    String snakeToCamel(String snakeCase) {
        if (!snakeCase.contains("_")) {
            return snakeCase;
        }
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                upperNext = true;
            } else {
                result.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return result.toString();
    }
}
