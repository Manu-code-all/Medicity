package com.medicity.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI medicityOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Medicity API")
                        .version("v1")
                        .description("""
                                Hospital management platform.

                                Booking is concurrency-safe: a slot admits exactly one
                                active appointment, enforced by a partial unique index
                                rather than by application-level checks.
                                """)
                        .contact(new Contact().name("Medicity")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
