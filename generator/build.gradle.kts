plugins {
  `kotlin-only`
  alias(libs.plugins.wire)
  `java-library`
  `publish-convention`
}

dependencies {
  implementation(libs.wire)
  implementation(libs.kotlinPoet)

  testImplementation(libs.wire.json)
  testImplementation(libs.moshi)
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
