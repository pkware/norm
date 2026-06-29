package com.pkware.gradle

import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPom

/**
 * Applies the POM metadata shared by every published `com.pkware.norm` artifact.
 *
 * @param project the project being published; supplies `name`/`description` defaults via the
 *   [pomName] and [pomDescription] accessors, which honor the optional `POM_NAME` and
 *   `POM_DESCRIPTION` Gradle properties.
 */
internal fun MavenPom.configureCommonPom(project: Project) {
  name.set(project.pomName)
  description.set(project.pomDescription)
  url.set("https://github.com/pkware/norm")

  organization {
    name.convention("PKWARE, Inc.")
    url.convention("https://www.pkware.com")
  }
  developers {
    developer {
      id.set("all")
      name.set("PKWARE, Inc.")
    }
  }
  scm {
    connection.set("scm:git:git://github.com/pkware/norm.git")
    developerConnection.set("scm:git:ssh://github.com/pkware/norm.git")
    url.set("https://github.com/pkware/norm")
  }
  licenses {
    license {
      name.set("MIT License")
      distribution.set("repo")
      url.set("https://github.com/pkware/norm/blob/main/LICENSE")
    }
  }
}

/** POM artifact name; defaults to the project name, overridable via the `POM_NAME` property. */
internal val Project.pomName: String
  get() = properties.getOrDefault("POM_NAME", name).toString()

/** POM description; defaults to the project name, overridable via the `POM_DESCRIPTION` property. */
internal val Project.pomDescription: String
  get() = properties.getOrDefault("POM_DESCRIPTION", name).toString()
