package io.github.mrzdream.campusflow.resource.application;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(long id) {
        super("Resource " + id + " was not found.");
    }
}
