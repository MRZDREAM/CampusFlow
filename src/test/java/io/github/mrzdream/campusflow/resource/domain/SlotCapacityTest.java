package io.github.mrzdream.campusflow.resource.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotCapacityTest {

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "2, -1", "2, 3"})
    void rejectsInvalidCapacity(int total, int reserved) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SlotCapacity(total, reserved));
    }

    @Test
    void reservingConsumesOnePlaceAndPreservesTheOriginalValue() {
        SlotCapacity before = new SlotCapacity(3, 1);
        SlotCapacity after = before.reserve();

        assertThat(after.reserved()).isEqualTo(2);
        assertThat(after.available()).isEqualTo(1);
        assertThat(before.reserved()).isEqualTo(1);
    }

    @Test
    void rejectsReservationWhenCapacityIsExhausted() {
        assertThatThrownBy(() -> new SlotCapacity(2, 2).reserve())
                .isInstanceOf(CapacityUnavailableException.class);
    }

    @Test
    void releasingRestoresExactlyOnePlace() {
        SlotCapacity before = new SlotCapacity(3, 2);
        SlotCapacity after = before.release();

        assertThat(after.reserved()).isEqualTo(1);
        assertThat(after.available()).isEqualTo(2);
        assertThat(before.reserved()).isEqualTo(2);
    }

    @Test
    void rejectsReleaseWhenNothingIsReserved() {
        assertThatIllegalStateException().isThrownBy(() -> new SlotCapacity(2, 0).release());
    }

    @Test
    void reserveAndReleaseRestoreCapacity() {
        SlotCapacity initial = new SlotCapacity(4, 0);

        assertThat(initial.reserve().release()).isEqualTo(initial);
    }

    @Test
    void maximumCapacityDoesNotOverflow() {
        SlotCapacity full = new SlotCapacity(Integer.MAX_VALUE, Integer.MAX_VALUE - 1).reserve();

        assertThat(full.available()).isZero();
        assertThatThrownBy(full::reserve).isInstanceOf(CapacityUnavailableException.class);
    }
}
