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

tasks.test {
  useJUnitPlatform {
    excludeTags("generateScannerBaseline")
  }
}

tasks.register<Test>("generateScannerBaseline") {
  description = "Rewrites the committed scanner baseline and the grammar sweep skip list"
  group = "verification"

  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath

  useJUnitPlatform {
    includeTags("generateScannerBaseline")
  }

  // The task writes into the source tree, so a cached or up-to-date result would leave the files stale.
  outputs.upToDateWhen { false }
  outputs.doNotCacheIf("it writes into the source tree") { true }
}
