plugins {
  `kotlin-only`
  alias(libs.plugins.wire)
  `java-library`
  id("com.pkware.gradle.publish")
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
