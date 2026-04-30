plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  compileOnlyApi(libs.postgresql)

  testImplementation(libs.bundles.mockito)
  testImplementation(libs.bundles.testcontainers)
  testRuntimeOnly(libs.postgresql)
}

kotlin {
  explicitApi()
}
