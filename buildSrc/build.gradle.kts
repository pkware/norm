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
  implementation(kotlin("gradle-plugin", libs.versions.kotlin.get()))
  implementation("com.diffplug.spotless:spotless-plugin-gradle:8.5.0")
  implementation("dev.detekt:dev.detekt.gradle.plugin:2.0.0-alpha.3")
}
