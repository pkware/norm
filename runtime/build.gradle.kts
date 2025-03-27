plugins {
  `kotlin-only`
  `java-library`
}

dependencies {
  compileOnlyApi(libs.postgresql)

  testImplementation(libs.mockito.kotlin)
}
