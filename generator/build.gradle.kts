plugins {
  `kotlin-conventions`
  alias(libs.plugins.wire)
  `java-library`
  `publish-conventions`
}

dependencies {
  implementation(libs.wire)
  implementation(libs.kotlinPoet)

  testImplementation(libs.wire.json)
  testImplementation(libs.moshi)
  testImplementation(libs.bundles.testcontainers)
  testImplementation(libs.postgresql)
}

wire {
  sourcePath {
    srcDir(project.rootDir.resolve("proto").toString())
  }

  kotlin {
    excludes = listOf("plugin.CodegenService")
  }
}

kotlin {
  explicitApi()
}
