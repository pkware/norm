package com.pkware.gradle

import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * Configures a module to publish to Maven Central via the vanniktech maven-publish plugin.
 *
 * Plain Kotlin libraries publish an empty javadoc jar (their public API is Kotlin). The
 * `java-gradle-plugin` module publishes via the [GradlePlugin] platform, which covers both the
 * plugin artifact and its auto-generated plugin-marker publication. Signing is applied only on CI.
 */
class PublishConventionPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    apply(plugin = "com.vanniktech.maven.publish.base")

    configure<MavenPublishBaseExtension> {
      if (plugins.hasPlugin("java-gradle-plugin")) {
        configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
      } else {
        configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
      }
      configureCommonPublishing(this@run)
    }
  }
}
