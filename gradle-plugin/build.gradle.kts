plugins {
  `kotlin-only`
  `kotlin-dsl`
  `java-gradle-plugin`
  `publish-convention`
}

gradlePlugin {
  plugins {
    register("norm-plugin") {
      id = "com.pkware.norm"
      implementationClass = "norm.gradle.NormPlugin"
    }
  }
}

dependencies {
  implementation(projects.generator)
  implementation(libs.wire.json)
  implementation(libs.moshi)
  implementation(kotlin("gradle-plugin"))

  testImplementation(gradleTestKit())
}

kotlin {
  explicitApi()
}
