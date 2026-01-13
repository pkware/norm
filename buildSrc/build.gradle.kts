plugins {
  `kotlin-dsl`
}

gradlePlugin {
  plugins {
    register("java-conventions-plugin") {
      id = "java-conventions"
      implementationClass = "com.pkware.gradle.JavaConventionsPlugin"
    }
    register("kotlin-conventions-plugin") {
      id = "kotlin-conventions"
      implementationClass = "com.pkware.gradle.KotlinConventionsPlugin"
    }
    register("publish-convention-plugin") {
      id = "publish-conventions"
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
