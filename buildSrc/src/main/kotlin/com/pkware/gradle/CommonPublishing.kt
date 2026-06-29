package com.pkware.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project

/**
 * Applies the publishing configuration shared by every published module: Maven Central coordinates,
 * auto-released Central Portal publishing, CI-only signing, and the [common POM][configureCommonPom].
 * The caller selects the platform (`configure(JavaLibrary(...))` or `configure(GradlePlugin(...))`)
 * before invoking this.
 *
 * @param project the project being published; supplies the Maven coordinates and POM metadata.
 */
internal fun MavenPublishBaseExtension.configureCommonPublishing(project: Project) {
  coordinates(project.group.toString(), project.name, project.version.toString())
  publishToMavenCentral(automaticRelease = true)
  if (System.getenv().containsKey("CI")) {
    signAllPublications()
  }
  pom { configureCommonPom(project) }
}
