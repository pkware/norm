plugins {
  `kotlin-only`
  `java-library`
  `publish-convention`
}

dependencies {
  compileOnlyApi(libs.postgresql)

  testImplementation(libs.bundles.mockito)
}

kotlin {
  explicitApi()
}
