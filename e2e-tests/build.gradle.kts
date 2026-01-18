import java.time.Duration

plugins {
  `kotlin-conventions`
}

dependencies {
  // NORM runtime (required by generated code)
  implementation(projects.runtime)

  // PostgreSQL JDBC driver
  implementation(libs.postgresql)

  // Testcontainers for real Postgres in Docker
  testImplementation(libs.bundles.testcontainers)
}

// Point test source set to generated code from test-scenarios
sourceSets {
  test {
    kotlin {
      // Include generated PostgresQueries.kt and Type.kt
      srcDir(rootProject.file("test-scenarios-basic/all_types/example"))
    }
  }
}

tasks.test {
  // E2E tests may take longer than unit tests
  timeout.set(Duration.ofMinutes(5))
  // Disable parallel execution - tests share database state
  systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
