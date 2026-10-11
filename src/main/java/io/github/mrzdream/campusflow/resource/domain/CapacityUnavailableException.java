package io.github.mrzdream.campusflow.resource.domain;

public class CapacityUnavailableException extends RuntimeException {

    public CapacityUnavailableException() {
        super("The time slot has no available capacity.");
    }
}
