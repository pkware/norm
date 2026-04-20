enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "norm"

include(
  "e2e-tests",
  "e2e-tests-micronaut",
  "e2e-tests-spring",
  "generator",
  "gradle-plugin",
  "runtime",
)

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins {
  id("com.gradle.develocity") version "4.4.1"
}

val isCiServer = System.getenv().containsKey("CI")

develocity {
  buildScan {
    termsOfUseUrl = "https://gradle.com/terms-of-service"
    termsOfUseAgree.set("yes")
    publishing.onlyIf { _ -> false }
    if (isCiServer) {
      tag("CI")
    }
  }
}
