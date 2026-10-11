package io.github.mrzdream.campusflow.resource.domain;

import java.time.Instant;
import java.util.Objects;

public record SlotWindow(Instant startsAt, Instant endsAt) {

    public SlotWindow {
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("The slot end must be after its start.");
        }
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }

    public boolean overlaps(SlotWindow other) {
        Objects.requireNonNull(other, "other must not be null");
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }
}
