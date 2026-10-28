package io.github.mrzdream.campusflow.resource.application;

import java.util.List;

import io.github.mrzdream.campusflow.resource.dto.CreateResourceRequest;
import io.github.mrzdream.campusflow.resource.dto.ResourcePageResponse;
import io.github.mrzdream.campusflow.resource.dto.ResourceResponse;
import io.github.mrzdream.campusflow.resource.domain.Resource;
import io.github.mrzdream.campusflow.resource.persistence.ResourceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ResourceService {

    private final ResourceRepository repository;

    public ResourceService(ResourceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ResourceResponse create(CreateResourceRequest request) {
        Resource resource = Resource.create(request.name(), request.description());
        Resource saved = repository.save(resource);
        return ResourceResponse.from(saved);
    }

    public ResourceResponse findById(long id) {
        Resource resource = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(id));
        return ResourceResponse.from(resource);
    }

    public ResourcePageResponse findAll(int page, int size) {
        PageRequest query = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<Resource> result = repository.findAll(query);
        List<ResourceResponse> content = result.getContent().stream()
                .map(ResourceResponse::from).toList();
        return new ResourcePageResponse(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
