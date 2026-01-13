plugins {
  `kotlin-conventions`
  `kotlin-dsl`
  `java-gradle-plugin`
  `publish-conventions`
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
