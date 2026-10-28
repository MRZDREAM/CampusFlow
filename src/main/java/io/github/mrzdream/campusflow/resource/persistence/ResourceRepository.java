package io.github.mrzdream.campusflow.resource.persistence;

import io.github.mrzdream.campusflow.resource.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, Long> {
}
