package io.github.mrzdream.campusflow.resource.api;

import java.net.URI;

import io.github.mrzdream.campusflow.resource.application.ResourceService;
import io.github.mrzdream.campusflow.resource.dto.CreateResourceRequest;
import io.github.mrzdream.campusflow.resource.dto.ResourcePageResponse;
import io.github.mrzdream.campusflow.resource.dto.ResourceResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody CreateResourceRequest request) {
        ResourceResponse response = service.create(request);
        URI location = URI.create("/api/v1/resources/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResourceResponse findById(@PathVariable @Positive long id) {
        return service.findById(id);
    }

    @GetMapping
    public ResourcePageResponse findAll(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.findAll(page, size);
    }
}
