import io.gitlab.arturbosch.detekt.Detekt
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  `kotlin-conventions`
  alias(libs.plugins.spring.kotlin)
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependencyManagement)
}

dependencies {
  // Norm runtime (required by generated code)
  implementation(projects.runtime)

  // Spring JDBC + transaction support (without Data JDBC ORM layer)
  implementation("org.springframework.boot:spring-boot-starter-jdbc")

  // Kotlin reflection - required by Spring for constructor discovery on Kotlin data classes
  runtimeOnly(kotlin("reflect"))

  // Database
  runtimeOnly(libs.postgresql)

  // Testing
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation(libs.testcontainers.postgresql)
}

// Include Norm-generated code (with @Component DI annotations)
sourceSets {
  main {
    kotlin {
      srcDir(rootProject.file("test-scenarios-frameworks/all_tables_behavior/spring"))
    }
  }
}

tasks.test {
  // Disable parallel execution - tests share database state
  systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}

// Disable detekt for E2E test module - detekt 1.23.8 has compatibility issues with Kotlin 2.2
// See: https://detekt.dev/docs/introduction/compatibility/
tasks.withType<Detekt>().configureEach {
  enabled = false
}

// Disable bootJar - this is a test-only module, not an executable Spring Boot application
tasks.named<BootJar>("bootJar") {
  enabled = false
}

// Enable regular jar task instead
tasks.named<Jar>("jar") {
  enabled = true
}
