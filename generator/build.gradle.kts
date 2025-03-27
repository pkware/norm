plugins {
  `kotlin-only`
  alias(libs.plugins.wire)
  id("com.pkware.gradle.publish")
}

dependencies {
  implementation(libs.wire.json)
  implementation(libs.moshi)
  implementation(libs.kotlinPoet)
}

wire {
  sourcePath {
    srcDir("src/main/proto")
  }

  kotlin {
    excludes = listOf("plugin.CodegenService")
  }
}
