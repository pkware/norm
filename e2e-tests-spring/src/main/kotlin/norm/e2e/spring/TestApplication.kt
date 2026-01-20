package norm.e2e.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

/**
 * Spring Boot application for E2E testing.
 *
 * This application demonstrates that Norm-generated entities with
 * `@Table` annotations work correctly with Spring Data JDBC repositories.
 */
@SpringBootApplication
@EnableJdbcRepositories
class TestApplication
