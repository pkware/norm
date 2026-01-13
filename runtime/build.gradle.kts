plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

dependencies {
  compileOnlyApi(libs.postgresql)

  testImplementation(libs.bundles.mockito)
}

kotlin {
  explicitApi()
}
