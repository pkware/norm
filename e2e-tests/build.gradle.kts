import java.time.Duration

plugins {
  `kotlin-conventions`
}

dependencies {
  // Norm runtime (required by generated code)
  implementation(projects.runtime)

  // PostgreSQL JDBC driver
  implementation(libs.postgresql)

  // Testcontainers for real Postgres in Docker
  testImplementation(libs.bundles.testcontainers)

  // Framework dependencies (compile-only, just to verify annotations are correct)
  // These verify that generated code compiles against actual framework annotation classes
  testCompileOnly("io.micronaut.data:micronaut-data-jdbc:4.0.0")
  testCompileOnly("org.springframework.data:spring-data-jdbc:3.2.0")
}

// Point test source set to generated code from test-scenarios
sourceSets {
  test {
    kotlin {
      // Include generated PostgresQueries.kt and Type.kt for E2E query tests
      srcDir(rootProject.file("test-scenarios-basic/all_types/example"))
      // Note: Framework-generated code (test-scenarios-frameworks/) is NOT included as source
      // because it would conflict with the all_types scenario (same package/class names).
      // Framework annotation verification is done via source file inspection instead.
    }
  }
}

tasks.test {
  // E2E tests may take longer than unit tests
  timeout.set(Duration.ofMinutes(5))
  // Disable parallel execution - tests share database state
  systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
