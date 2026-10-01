plugins {
  `kotlin-conventions`
  alias(libs.plugins.ksp)
}

dependencies {
  implementation(projects.runtime)
  implementation(libs.postgresql)
  implementation(libs.micronaut.inject)

  ksp(libs.micronaut.inject.kotlin)
}

sourceSets {
  main {
    kotlin {
      srcDir(layout.settingsDirectory.dir("test-scenarios-frameworks/comprehensive/micronaut-di"))
    }
  }
}
