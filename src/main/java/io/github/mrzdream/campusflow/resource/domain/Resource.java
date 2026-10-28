package io.github.mrzdream.campusflow.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "resources")
public class Resource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private ResourceStatus status;

    protected Resource() {
    }

    public static Resource create(String name, String description) {
        String normalizedName = name == null ? "" : name.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("Resource name must contain text and be at most 120 characters.");
        }
        if (description != null && description.length() > 1000) {
            throw new IllegalArgumentException("Resource description must be at most 1000 characters.");
        }
        Resource resource = new Resource();
        resource.name = normalizedName;
        resource.description = description == null ? "" : description;
        resource.status = ResourceStatus.OPEN;
        return resource;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ResourceStatus getStatus() {
        return status;
    }
}
