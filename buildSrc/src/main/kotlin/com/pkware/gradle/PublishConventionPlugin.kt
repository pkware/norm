package com.pkware.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

/**
 * Plugin for projects that publish.
 */
class PublishConventionPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    apply<MavenPublishPlugin>()

    configure<PublishingExtension> {
      publications {
        register<MavenPublication>("mavenJava") {
          from(components["java"])
          pom {
            name.set(pomName)
            description.set(pomDescription)
            packaging = pomPackaging
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
        }
      }
      repositories {
        maven {
          name = "MavenCentral"
          url = uri(if (version.toString().isReleaseBuild) releaseRepositoryUrl else snapshotRepositoryUrl)
          credentials {
            username = repositoryUsername
            password = repositoryPassword
          }
        }
      }
    }

    val isCiServer = System.getenv().containsKey("CI")
    if (isCiServer) {
      pluginManager.apply(SigningPlugin::class.java)
      configure<SigningExtension> {
        // Signing credentials are stored as secrets in GitHub.
        // See https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials for more information.

        useInMemoryPgpKeys(
          signingKeyId,
          signingKey,
          signingPassword,
        )

        sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
      }
    }

    extensions.configure<JavaPluginExtension> {
      withJavadocJar()
      withSourcesJar()
    }
  }
}

val String.isReleaseBuild
  get() = !contains("SNAPSHOT")

val Project.releaseRepositoryUrl: String
  get() = properties.getOrDefault(
    "RELEASE_REPOSITORY_URL",
    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2",
  ).toString()

val Project.snapshotRepositoryUrl: String
  get() = properties.getOrDefault(
    "SNAPSHOT_REPOSITORY_URL",
    "https://central.sonatype.com/repository/maven-snapshots/",
  ).toString()

val Project.repositoryUsername: String
  get() = properties.getOrDefault("NEXUS_USERNAME", "").toString()

val Project.repositoryPassword: String
  get() = properties.getOrDefault("NEXUS_PASSWORD", "").toString()

val Project.signingKeyId: String
  get() = properties.getOrDefault("SIGNING_KEY_ID", "").toString()

val Project.signingKey: String
  get() = properties.getOrDefault("SIGNING_KEY", "").toString()

val Project.signingPassword: String
  get() = properties.getOrDefault("SIGNING_PASSWORD", "").toString()

val Project.pomPackaging: String
  get() = properties.getOrDefault("POM_PACKAGING", "jar").toString()

val Project.pomName: String
  get() = properties.getOrDefault("POM_NAME", name).toString()

val Project.pomDescription: String
  get() = properties.getOrDefault("POM_NAME", name).toString()

