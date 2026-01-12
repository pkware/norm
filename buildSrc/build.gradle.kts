plugins {
  `kotlin-dsl`
}

gradlePlugin {
  plugins {
    register("kotlin-only-conventions-plugin") {
      id = "kotlin-only"
      implementationClass = "com.pkware.gradle.KotlinOnlyPlugin"
    }
    register("publish-convention-plugin") {
      id = "publish-convention"
      implementationClass = "com.pkware.gradle.PublishConventionPlugin"
    }
  }
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation(kotlin("gradle-plugin"))
  implementation("com.diffplug.spotless:spotless-plugin-gradle:7.2.1")
  implementation("io.gitlab.arturbosch.detekt:io.gitlab.arturbosch.detekt.gradle.plugin:1.23.8")
}
