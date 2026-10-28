package io.github.mrzdream.campusflow.resource.dto;

import java.util.List;

public record ResourcePageResponse(List<ResourceResponse> content, int page, int size,
                                   long totalElements, int totalPages) {

    public ResourcePageResponse {
        content = List.copyOf(content);
    }
}
