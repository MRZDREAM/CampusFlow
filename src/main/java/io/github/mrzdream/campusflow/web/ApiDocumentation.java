package io.github.mrzdream.campusflow.web;

import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApiDocumentation {
    private static final String BEARER = "bearerJwt";
    private static final Set<PathItem.HttpMethod> WRITES = Set.of(PathItem.HttpMethod.POST, PathItem.HttpMethod.PATCH);

    @Bean
    OpenAPI campusApi() {
        return new OpenAPI().info(new Info().title("CampusFlow").version("1.0")
                        .description("Campus resource scheduling, reservations and waitlist."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer campusAuthorization() {
        return api -> {
            if (api.getPaths() != null) {
                api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                    if (path.startsWith("/api/v1/reservations") || path.startsWith("/api/v1/reservation-requests")
                            || path.startsWith("/api/v1/waitlist")
                            || (path.startsWith("/api/v1/resources") && WRITES.contains(method))) {
                        operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
                    }
                }));
            }
        };
    }
}
