package io.github.mrzdream.campusflow.resource.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SlotWindowTest {

    private static final Instant START = Instant.parse("2026-10-12T09:00:00Z");
    private static final Instant END = Instant.parse("2026-10-12T10:00:00Z");
    private static final SlotWindow WINDOW = new SlotWindow(START, END);

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-12T09:00:00Z", "2026-10-12T08:00:00Z"})
    void rejectsEmptyOrReversedWindow(String end) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SlotWindow(START, Instant.parse(end)));
    }

    @Test
    void rejectsMissingBoundaries() {
        assertThatNullPointerException().isThrownBy(() -> new SlotWindow(null, END));
        assertThatNullPointerException().isThrownBy(() -> new SlotWindow(START, null));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12T08:59:59Z, false",
            "2026-10-12T09:00:00Z, true",
            "2026-10-12T09:30:00Z, true",
            "2026-10-12T10:00:00Z, false"
    })
    void usesInclusiveStartAndExclusiveEnd(String instant, boolean expected) {
        assertThat(WINDOW.contains(Instant.parse(instant))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-12T08:00:00Z, 2026-10-12T09:00:00Z, false",
            "2026-10-12T10:00:00Z, 2026-10-12T11:00:00Z, false",
            "2026-10-12T08:30:00Z, 2026-10-12T09:30:00Z, true",
            "2026-10-12T09:30:00Z, 2026-10-12T10:30:00Z, true",
            "2026-10-12T09:15:00Z, 2026-10-12T09:45:00Z, true",
            "2026-10-12T08:00:00Z, 2026-10-12T11:00:00Z, true",
            "2026-10-12T09:00:00Z, 2026-10-12T10:00:00Z, true"
    })
    void overlapIsSymmetricAndAdjacentWindowsDoNotConflict(String start, String end, boolean expected) {
        SlotWindow other = new SlotWindow(Instant.parse(start), Instant.parse(end));

        assertThat(WINDOW.overlaps(other)).isEqualTo(expected);
        assertThat(other.overlaps(WINDOW)).isEqualTo(expected);
    }

    @Test
    void rejectsMissingComparisonInputs() {
        assertThatNullPointerException().isThrownBy(() -> WINDOW.contains(null));
        assertThatNullPointerException().isThrownBy(() -> WINDOW.overlaps(null));
    }
}
