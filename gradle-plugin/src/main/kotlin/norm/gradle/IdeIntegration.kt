package norm.gradle

import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * IDE integration utilities for improving the developer experience when using Norm with IntelliJ IDEA.
 *
 * Provides two opt-in mechanisms:
 * 1. Registers generated sources via Kotlin's `generatedKotlin` API (Kotlin 2.3.0+) when available,
 *    which enables future IntelliJ support for triggering generation during sync.
 * 2. Hooks into `gradle-idea-ext`'s `afterSync` trigger (if the user has applied the plugin),
 *    which runs the generation task after every Gradle sync — including first import.
 */
internal object IdeIntegration {

  /**
   * Registers the output of [generateTask] as a source directory on [sourceSet] using the best available API.
   *
   * On Kotlin 2.3.0+, uses `KotlinSourceSet.generatedKotlin` which signals to the Kotlin toolchain that these
   * sources are generated. On older versions, falls back to `KotlinSourceSet.kotlin.srcDir()`.
   */
  fun registerGeneratedSources(sourceSet: KotlinSourceSet, generateTask: TaskProvider<NormGenerateTask>) {
    try {
      // KotlinSourceSet.getGeneratedKotlin() was added in Kotlin 2.3.0.
      // The return type is SourceDirectorySet, which is a Gradle core type always on the classpath.
      val generatedKotlin = sourceSet.javaClass.getMethod("getGeneratedKotlin").invoke(sourceSet)
      generatedKotlin.javaClass.getMethod("srcDir", Any::class.java).invoke(generatedKotlin, generateTask)
    } catch (_: NoSuchMethodException) {
      sourceSet.kotlin.srcDir(generateTask)
    }
  }

  /**
   * If the user has applied `org.jetbrains.gradle.plugin.idea-ext`, configures [generateTask] to run
   * after every IntelliJ Gradle sync. This ensures generated sources exist on first project import.
   *
   * All access to `idea-ext` types is via reflection to avoid a compile-time dependency.
   */
  fun configureIdeaExtAfterSync(project: Project, generateTask: TaskProvider<NormGenerateTask>) {
    project.rootProject.pluginManager.withPlugin("org.jetbrains.gradle.plugin.idea-ext") {
      val ideaModel = project.rootProject.extensions.findByName("idea") ?: return@withPlugin
      val ideaProject = ideaModel.javaClass.getMethod("getProject").invoke(ideaModel) ?: return@withPlugin
      val settings = (ideaProject as ExtensionAware).extensions.findByName("settings") ?: return@withPlugin
      val taskTriggers = (settings as ExtensionAware).extensions.findByName("taskTriggers") ?: return@withPlugin
      taskTriggers.javaClass.getMethod("afterSync", Array<Any>::class.java)
        .invoke(taskTriggers, arrayOf(generateTask))
    }
  }
}
