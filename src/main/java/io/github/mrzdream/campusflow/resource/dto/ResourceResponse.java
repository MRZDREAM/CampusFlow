package io.github.mrzdream.campusflow.resource.dto;

import io.github.mrzdream.campusflow.resource.domain.Resource;
import io.github.mrzdream.campusflow.resource.domain.ResourceStatus;

public record ResourceResponse(Long id, String name, String description, ResourceStatus status) {

    public static ResourceResponse from(Resource resource) {
        return new ResourceResponse(resource.getId(), resource.getName(),
                resource.getDescription(), resource.getStatus());
    }
}
