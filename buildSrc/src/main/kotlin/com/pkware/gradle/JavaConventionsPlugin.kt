package com.pkware.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension

/**
 * Plugin for projects that support Java.
 */
class JavaConventionsPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    apply<JavaPlugin>()
    configure<JavaPluginExtension> {
      toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
      }
    }

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    configure<TestingExtension> {
      suites.withType<JvmTestSuite>().configureEach {
        useJUnitJupiter(libs.findVersion("junit").get().toString())

        dependencies {
          implementation(libs.findLibrary("junit-params").get().get().toString())
        }

        targets.all {
          testTask.configure {
            testLogging {
              events("passed", "skipped", "failed")
            }

            // Run tests in parallel
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
          }
        }
      }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
      isPreserveFileTimestamps = false
      isReproducibleFileOrder = true
    }
  }
}
