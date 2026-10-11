package io.github.mrzdream.campusflow.resource.domain;

public record SlotCapacity(int total, int reserved) {

    public SlotCapacity {
        if (total <= 0) {
            throw new IllegalArgumentException("Total capacity must be positive.");
        }
        if (reserved < 0 || reserved > total) {
            throw new IllegalArgumentException("Reserved capacity must be between zero and total capacity.");
        }
    }

    public int available() {
        return total - reserved;
    }

    public SlotCapacity reserve() {
        if (available() == 0) {
            throw new CapacityUnavailableException();
        }
        return new SlotCapacity(total, reserved + 1);
    }

    public SlotCapacity release() {
        if (reserved == 0) {
            throw new IllegalStateException("Cannot release capacity without an active reservation.");
        }
        return new SlotCapacity(total, reserved - 1);
    }
}
