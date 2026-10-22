plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  implementation(libs.chicory.runtime)
  implementation(libs.chicory.wasi)
  implementation(libs.kotlinPoet)

  testImplementation(libs.bundles.mockito)
  testImplementation(libs.bundles.testcontainers)
  testImplementation(libs.postgresql)
  testImplementation(libs.commonmark)
}

kotlin {
  explicitApi()
}
