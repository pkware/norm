enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "norm"

include(
  "examples",
  "generator",
  "gradle-plugin",
  "runtime",
)

pluginManagement {
  repositories {
    mavenCentral()
    maven {
      url = uri("https://packages.smartcrypt.com/repository/maven-group/")
      name = "pkwareNexus"
      credentials(org.gradle.api.credentials.PasswordCredentials::class)
    }
    gradlePluginPortal()
  }
}

plugins {
  id("com.pkware.gradle.gradle-enterprise") version "4.8.1"
  id("com.ryandens.temurin-binaries-repository") version "0.4.1"
}
