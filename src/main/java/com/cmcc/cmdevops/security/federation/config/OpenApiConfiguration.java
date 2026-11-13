package com.cmcc.cmdevops.security.federation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI federationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Federation Gateway API")
                        .description("跨源联邦查询与本体驱动智能问数网关")
                        .version("1.0")
                        .license(new License().name("Apache-2.0")))
                .components(new Components().addSecuritySchemes(
                        "bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT / opaque")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
