package com.pkware.norm.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.uppercaseFirstChar

public class NormPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    val normConfiguration = configurations.create("norm") {
      isCanBeConsumed = false
      isCanBeResolved = true
    }

    dependencies {
      // FIXME official version instead of SNAPSHOT
      add("norm", "com.pkware.norm:generator:1.0.0-SNAPSHOT")
    }

    val norm = extensions.create(
      NormExtension::class.java,
      "norm",
      NormExtensionImplementation::class.java,
      this,
    )
// 		TODO("Gradle task to download sqlc if not available")

// 		tasks.withType<KotlinJvmCompile>().configureEach {
// 			compilerOptions {
// 				jvmTarget.set(JvmTarget.JVM_11)
// 			}
// 		}

    val normClasspathFiles = project.provider(normConfiguration::resolve)

    norm.sqlSources.all {
      val yamlTask =
        tasks.register<GenerateYamlTask>("generateNormYaml${name.uppercaseFirstChar()}", this, normClasspathFiles)
      val generateCodeTask = tasks.register<GenerateCodeTask>("generateNormCode${name.uppercaseFirstChar()}", this)
      generateCodeTask.configure {
        dependsOn(yamlTask)
      }
    }

// 		TODO("Add the generated code to the Kotlin sourceset")
  }

  public companion object {
    public const val NORM_GENERATED_CODE: String = "generated/norm"
  }
}
