package io.github.mrzdream.campusflow.resource.api;

import java.util.List;
import java.util.stream.Stream;

import io.github.mrzdream.campusflow.resource.application.ResourceNotFoundException;
import io.github.mrzdream.campusflow.resource.application.ResourceService;
import io.github.mrzdream.campusflow.resource.domain.ResourceStatus;
import io.github.mrzdream.campusflow.resource.dto.CreateResourceRequest;
import io.github.mrzdream.campusflow.resource.dto.ResourcePageResponse;
import io.github.mrzdream.campusflow.resource.dto.ResourceResponse;
import io.github.mrzdream.campusflow.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResourceController.class)
@Import(ApiExceptionHandler.class)
class ResourceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ResourceService service;

    @Test
    void validCreationReturnsCreatedAndLocation() throws Exception {
        CreateResourceRequest input = new CreateResourceRequest("GPU", "共享设备");
        when(service.create(input)).thenReturn(new ResourceResponse(42L, "GPU", "共享设备", ResourceStatus.OPEN));

        mvc.perform(post("/api/v1/resources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"GPU\",\"description\":\"共享设备\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/resources/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("OPEN"));
        verify(service).create(input);
    }

    @Test
    void detailReturnsPublicResourceFields() throws Exception {
        when(service.findById(42)).thenReturn(new ResourceResponse(42L, "GPU", "", ResourceStatus.OPEN));

        mvc.perform(get("/api/v1/resources/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GPU"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.hibernateLazyInitializer").doesNotExist());
    }

    @Test
    void missingResourceReturnsProblemDetails() throws Exception {
        when(service.findById(42)).thenThrow(new ResourceNotFoundException(42));

        mvc.perform(get("/api/v1/resources/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/resources/42"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void listUsesBoundedDefaultPagination() throws Exception {
        ResourceResponse resource = new ResourceResponse(42L, "GPU", "", ResourceStatus.OPEN);
        when(service.findAll(0, 20)).thenReturn(new ResourcePageResponse(List.of(resource), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(service).findAll(0, 20);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidFieldsNeverReachTheService(String body) throws Exception {
        mvc.perform(post("/api/v1/resources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(service);
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{}", "{\"name\":null}", "{\"name\":\"   \"}", "{\"name\":\"\u2003\"}",
                "{\"name\":\"" + "x".repeat(121) + "\"}",
                "{\"name\":\"GPU\",\"description\":\"" + "x".repeat(1001) + "\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "[]"})
    void unreadableBodyReturnsAStableError(String body) throws Exception {
        mvc.perform(post("/api/v1/resources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Request body must be a valid JSON object."));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"0,VALIDATION_FAILED", "-1,VALIDATION_FAILED", "abc,INVALID_REQUEST"})
    void invalidIdentifierNeverReachesTheService(String id, String code) throws Exception {
        mvc.perform(get("/api/v1/resources/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "page=-1&size=20,VALIDATION_FAILED",
            "page=0&size=0,VALIDATION_FAILED",
            "page=0&size=101,VALIDATION_FAILED",
            "page=10001&size=100,VALIDATION_FAILED",
            "page=abc&size=1,INVALID_REQUEST",
            "page=0&size=abc,INVALID_REQUEST",
            "page=2147483648&size=1,INVALID_REQUEST"
    })
    void invalidPaginationNeverReachesTheService(String query, String code) throws Exception {
        mvc.perform(get("/api/v1/resources?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
        verifyNoInteractions(service);
    }
}
