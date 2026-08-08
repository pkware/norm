plugins {
  `kotlin-conventions`
  alias(libs.plugins.spring.kotlin)
}

dependencies {
  // Spring Boot BOM supplies versions for the unversioned starters below. The Spring Boot Gradle
  // plugin is deliberately not applied: it eagerly reads `Project.group` of every subproject when
  // constructing its `BootJar` task, which is an Isolated Projects violation. This module is
  // test-only and never produces an executable boot jar, so the BOM alone is enough.
  //
  // Note this is not identical to the `io.spring.dependency-management` plugin the boot plugin used
  // to bring along: that plugin *forces* its managed versions, while a BOM platform only
  // participates in normal highest-wins conflict resolution. Versions declared in
  // `gradle/libs.versions.toml` therefore win over Boot's when they are newer (JUnit, for example).
  implementation(platform(libs.spring.boot.bom))
  testImplementation(platform(libs.spring.boot.bom))

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
      srcDir(layout.settingsDirectory.dir("test-scenarios-frameworks/comprehensive/spring"))
    }
  }
}

tasks.test {
  // Disable parallel execution - tests share database state
  systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
