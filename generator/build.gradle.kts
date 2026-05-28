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
}

kotlin {
  explicitApi()
}
