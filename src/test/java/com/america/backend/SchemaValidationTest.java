package com.america.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** database/schema.sql 을 적용한 DB에서 JPA 엔티티 매핑이 일치하는지 검증합니다 (H2 PostgreSQL 호환 모드). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:database/schema.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "ai.grpc.internal-key=test-internal-key"
})
@ActiveProfiles("test")
class SchemaValidationTest {

    @Test
    void entitiesMatchSchema() {
    }
}
