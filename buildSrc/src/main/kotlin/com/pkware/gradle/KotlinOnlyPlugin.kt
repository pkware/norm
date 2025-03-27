package com.pkware.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByType

/**
 * Plugin for projects that support only Kotlin, not Java.
 */
class KotlinOnlyPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {

    // Configure the PKWARE Conventions Java plugin to not run linters we don't care about, like PMD, Spotbugs, etc.
    // Our code is Kotlin, and we have Kotlin analyzers.
    // See https://bitbucket.org/pkware-engineering/conventions
    extra.set("analyzeGroovy", "false")
    extra.set("analyzeJacoco", "false")
    extra.set("analyzePmd", "false")
    extra.set("analyzeSpotbugs", "false")
    extra.set("analyzeCheckstyle", "false")

    pluginManager.apply("com.pkware.gradle.kotlin")
    extensions.getByType<PkJavaPluginExtension>().apply {
      targetJavaVersion.set(21)
    }
  }
}
