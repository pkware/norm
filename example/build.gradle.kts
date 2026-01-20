import norm.generator.Framework
import org.gradle.kotlin.dsl.version

plugins {
  kotlin("jvm") version "2.2.21"
  alias(libs.plugins.norm)
}

dependencies {
  implementation(libs.postgresql)
}

repositories {
  mavenCentral()
}

norm {
  databases {
    register("example") {
      packageName = "example"
      schemas.addAll("src/main/sql/schema.sql")
      queries.addAll("src/main/sql/queries.sql")
      frameworks = setOf(Framework.SPRING_DATA_JDBC, Framework.MICRONAUT_DATA_JDBC)
    }
  }
}
