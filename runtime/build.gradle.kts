plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  testImplementation(libs.bundles.mockito)
}

kotlin {
  explicitApi()
}
