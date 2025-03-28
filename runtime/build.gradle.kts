plugins {
  `kotlin-only`
  `java-library`
  id("com.pkware.gradle.publish")
}

dependencies {
  compileOnlyApi(libs.postgresql)

  testImplementation(libs.mockito.kotlin)
}
