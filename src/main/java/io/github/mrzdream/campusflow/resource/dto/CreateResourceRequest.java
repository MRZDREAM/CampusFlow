package io.github.mrzdream.campusflow.resource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateResourceRequest(
        @NotBlank(message = "Name must contain text.")
        @Size(max = 120, message = "Name must be at most 120 characters.")
        String name,
        @Size(max = 1000, message = "Description must be at most 1000 characters.")
        String description) {

    public CreateResourceRequest {
        if (name != null) {
            name = name.strip();
        }
    }
}
