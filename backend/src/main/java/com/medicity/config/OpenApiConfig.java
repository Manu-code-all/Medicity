package com.medicity.config;

import com.medicity.patient.ActingPatient;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
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

    /**
     * Documents {@value ActingPatient#HEADER} on the routes that act "as the
     * patient". It is read in one place (ActingPatient), never as a controller
     * parameter, so springdoc cannot find it by itself.
     */
    @Bean
    public OpenApiCustomizer actingPatientHeader() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            boolean asPatient = path.startsWith("/api/v1/patients/me") && !path.startsWith("/api/v1/patients/me/family")
                    || path.equals("/api/v1/appointments") && method == PathItem.HttpMethod.POST
                    || path.equals("/api/v1/appointments/mine");
            if (asPatient) {
                op.addParametersItem(new HeaderParameter()
                        .name(ActingPatient.HEADER)
                        .required(false)
                        .schema(new StringSchema().format("uuid"))
                        .description("Act for a family member this account manages; omit to act for the account"
                                + " holder. An id that is not the account's own or its family answers 404."));
            }
        }));
    }
}
