package com.nexusiq.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Standard pagination envelope for every list endpoint (docs/API/API_DESIGN.md). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
