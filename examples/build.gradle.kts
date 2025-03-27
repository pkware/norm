plugins {
  `kotlin-only`
//  alias(libs.plugins.norm)
}

dependencies {
  implementation(libs.postgresql)
  // TODO Should probably be applied by a Gradle plugin that also sets up sqlc
  implementation(projects.runtime)
}

// norm {
//  databases {
//    create("Example") {
//      packageName = "example"
//      sqlcConfigurationFile = ""
//    }
//  }
// }
