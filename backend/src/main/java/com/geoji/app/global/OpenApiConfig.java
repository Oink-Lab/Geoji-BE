package com.geoji.app.global;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info().title("거지앱 API").version("v1"))
            .components(new Components().addSecuritySchemes(BEARER_AUTH,
                new SecurityScheme().type(SecurityScheme.Type.HTTP)
                    .scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer protectedOperations() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/v1/")
                || (path.startsWith("/api/v1/auth/")
                    && !path.equals("/api/v1/auth/logout"))) {
                return;
            }
            item.readOperations().forEach(operation -> operation.addSecurityItem(
                new SecurityRequirement().addList(BEARER_AUTH)));
        });
    }
}
