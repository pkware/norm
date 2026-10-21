plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  implementation(libs.kotlinPoet)

  testImplementation(libs.bundles.mockito)
  testImplementation(libs.bundles.testcontainers)
  testImplementation(libs.postgresql)
  testImplementation(libs.commonmark)
  testImplementation(libs.chicory.runtime)
  testImplementation(libs.chicory.wasi)
  testImplementation(libs.gson)
}

kotlin {
  explicitApi()
}
