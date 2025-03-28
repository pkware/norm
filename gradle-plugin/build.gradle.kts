plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  `kotlin-only`
  id("com.pkware.gradle.publish")
}

gradlePlugin {
  plugins {
    register("norm-plugin") {
      id = "com.pkware.norm"
      implementationClass = "com.pkware.norm.gradle.NormPlugin"
    }
  }
}

dependencies {
  implementation(projects.generator)
  implementation(libs.wire.json)
  implementation(libs.moshi)
  implementation(kotlin("gradle-plugin"))
}
