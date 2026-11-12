package com.america.backend.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI americaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AM-ERICA Backend API")
                        .version("v1")
                        .description("""
                                매장 대시보드 · AI 채팅 · 매출 분석 · 콘텐츠 관리를 위한 중앙 API입니다.
                                1) /api/auth/signup 또는 /api/auth/login 으로 accessToken을 받고
                                2) 오른쪽 위 Authorize 버튼에 토큰을 입력한 뒤 다른 API를 호출하세요.
                                """))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
