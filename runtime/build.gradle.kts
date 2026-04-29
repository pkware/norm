plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  testImplementation(libs.bundles.mockito)
  testImplementation(libs.bundles.testcontainers)
  testRuntimeOnly(libs.postgresql)
}

kotlin {
  explicitApi()
}
