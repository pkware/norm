package com.pkware.gradle

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension

/**
 * Plugin for projects that support only Kotlin, not Java.
 */
class KotlinOnlyPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {

    pluginManager.apply("org.jetbrains.kotlin.jvm")
    configure<KotlinJvmExtension> {
      jvmToolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val ktlintVersion = libs.findVersion("ktlint").get().toString()

    apply<SpotlessPlugin>()
    configure<SpotlessExtension> {
      kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
      }
      kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
      }
    }

    apply<DetektPlugin>()
    configure<DetektExtension> {
      buildUponDefaultConfig = true
      parallel = true
      config.from("$rootDir/detekt.yml")
    }
    configurations.named("detektPlugins") {
      dependencies.add(project.dependencies.create("com.pkware.detekt:import-extension:1.2.0"))
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
      isPreserveFileTimestamps = false
      isReproducibleFileOrder = true
    }

    configure<TestingExtension> {
      suites.withType<JvmTestSuite>().configureEach {
        useJUnitJupiter(libs.findVersion("junit").get().toString())

        dependencies {
          implementation(libs.findLibrary("junit-params").get().get().toString())
          implementation(libs.findLibrary("truth").get().get().toString())
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
  }
}
