package com.client360.common.web;

import com.client360.common.api.ApiException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Offset pagination envelope (SPEC.md §4.5) — for bounded, sortable admin lists only. The
 * interaction timeline and the audit log use {@link KeysetPage}; never {@code OFFSET} a large
 * table.
 */
public record OffsetPage<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public static <E, T> OffsetPage<T> of(Page<E> page, Function<E, T> mapper) {
        return new OffsetPage<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    /**
     * Builds a page request from {@code ?page=0&size=25&sort=createdAt,desc}. The sort field must
     * be one of {@code sortable} (API name → entity property); anything else is {@code 400}.
     */
    public static PageRequest request(
            Integer page, Integer size, String sort, Map<String, String> sortable, Sort fallback) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.validation("page", "must be zero or greater");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.validation("size", "must be between 1 and " + MAX_SIZE);
        }
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(p, s, fallback);
        }
        String[] parts = sort.split(",", -1);
        String property = sortable.get(parts[0].trim());
        if (property == null || parts.length > 2) {
            throw ApiException.validation(
                    "sort", "must be one of " + sortable.keySet() + ", optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim().toLowerCase()) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw ApiException.validation("sort", "direction must be asc or desc");
            };
        }
        // Tie-break on id so a page boundary is deterministic.
        return PageRequest.of(p, s, Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
