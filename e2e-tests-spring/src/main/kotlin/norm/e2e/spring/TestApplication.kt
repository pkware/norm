package norm.e2e.spring

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Spring Boot application for E2E testing.
 *
 * Demonstrates that Norm-generated `PostgresQueries` (annotated with `@Component`) integrates
 * correctly with Spring's DI and transaction infrastructure.
 */
@SpringBootApplication(scanBasePackages = ["norm.e2e.spring", "example"])
class TestApplication
