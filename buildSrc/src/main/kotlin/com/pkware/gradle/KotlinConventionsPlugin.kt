package com.pkware.gradle

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Plugin for projects that support Kotlin.
 */
class KotlinConventionsPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    apply<JavaConventionsPlugin>()
    pluginManager.apply("org.jetbrains.kotlin.jvm")

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
      dependencies.add(libs.findLibrary("detekt-imports").get().get())
    }
    val buildDirectory = layout.buildDirectory.get()
    val generatedFilesWindows = "$buildDirectory\\generated"
    val generatedFilesUnix = "$buildDirectory/generated"
    // configureEach for lazy configuration
    tasks.withType<Detekt>().configureEach {
      jvmTarget = tasks.named<KotlinCompile>("compileKotlin").get().compilerOptions.jvmTarget.get().target
      exclude {
        // Work around https://github.com/detekt/detekt/issues/4743
        val absolutePath = it.file.absolutePath
        absolutePath.startsWith(generatedFilesUnix) || absolutePath.startsWith(generatedFilesWindows)
      }
    }

    configure<TestingExtension> {
      suites.withType<JvmTestSuite>().configureEach {
        dependencies {
          implementation(libs.findLibrary("assertk").get().get().toString())
        }
      }
    }

    // If the project is a Kotlin library, activate explicit API mode.
    plugins.withType<JavaLibraryPlugin> {
      extensions.getByType<KotlinJvmProjectExtension>().explicitApi()
    }
  }
}
