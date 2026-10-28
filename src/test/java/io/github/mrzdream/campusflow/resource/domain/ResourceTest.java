package io.github.mrzdream.campusflow.resource.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ResourceTest {

    @Test
    void creationNormalizesNameAndUsesServerOwnedDefaults() {
        Resource resource = Resource.create("  GPU 工作站  ", "共享设备");

        assertThat(resource.getId()).isNull();
        assertThat(resource.getName()).isEqualTo("GPU 工作站");
        assertThat(resource.getDescription()).isEqualTo("共享设备");
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.OPEN);
    }

    @Test
    void missingDescriptionBecomesEmptyText() {
        assertThat(Resource.create("GPU", null).getDescription()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankNameIsRejected(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> Resource.create(name, null));
    }

    @Test
    void nullNameIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Resource.create(null, null));
    }

    @Test
    void oversizedNameIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Resource.create("x".repeat(121), null));
    }

    @Test
    void oversizedDescriptionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Resource.create("GPU", "x".repeat(1001)));
    }

    @Test
    void maximumLengthsAreAccepted() {
        Resource resource = Resource.create("  " + "x".repeat(120) + "  ", "x".repeat(1000));
        assertThat(resource.getName()).hasSize(120);
        assertThat(resource.getDescription()).hasSize(1000);
    }
}
