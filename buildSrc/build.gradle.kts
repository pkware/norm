import org.gradle.api.credentials.PasswordCredentials

plugins {
  `kotlin-dsl`
}

gradlePlugin {
  plugins {
    register("kotlin-only-conventions-plugin") {
      id = "kotlin-only"
      implementationClass = "com.pkware.gradle.KotlinOnlyPlugin"
    }
  }
}

repositories {
  mavenCentral()
  maven {
    url = uri("https://packages.smartcrypt.com/repository/maven-group/")
    name = "pkwareNexus"
    credentials(PasswordCredentials::class)
  }
  gradlePluginPortal()
}

dependencies {
  implementation(kotlin("gradle-plugin"))
  implementation("com.pkware:pkware-conventions:4.8.1")
}
