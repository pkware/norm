import org.gradle.kotlin.dsl.version

plugins {
  kotlin("jvm") version "2.1.20"
  // TODO Use the latest version, and don't use a SNAPSHOT
  id("com.pkware.norm") version "1.0.0-SNAPSHOT"
}

dependencies {
  implementation("org.postgresql:postgresql:42.7.7")
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
    }
  }
}
