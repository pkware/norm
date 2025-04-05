enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "norm"

include(
  "generator",
  "gradle-plugin",
  "runtime",
)

pluginManagement {
  repositories {
    mavenCentral()
    mavenLocal()
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
}
