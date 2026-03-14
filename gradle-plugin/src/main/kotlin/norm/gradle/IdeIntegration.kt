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
   * Registers the output of [generateTask] as a source directory on [sourceSet].
   *
   * Always registers via `KotlinSourceSet.kotlin.srcDir()` so that all compilation tasks — including
   * KSP's `kspKotlin` — receive the generated sources and the correct task dependency is established.
   * KSP reads from `kotlin.srcDirs`, not from `generatedKotlin`.
   *
   * On Kotlin 2.3.0+, also registers via `KotlinSourceSet.generatedKotlin` for IDE integration:
   * this signals to IntelliJ that it should run [generateTask] during Gradle sync to avoid missing
   * sources on first project import. IntelliJ deduplicates source roots by path, so the directory
   * appears once and is marked as a generated source root.
   */
  fun registerGeneratedSources(sourceSet: KotlinSourceSet, generateTask: TaskProvider<NormGenerateTask>) {
    // Always register for compilation, including KSP's kspKotlin task.
    sourceSet.kotlin.srcDir(generateTask)

    // Also register with the generatedKotlin API (Kotlin 2.3.0+) for IDE integration.
    try {
      // KotlinSourceSet.getGeneratedKotlin() was added in Kotlin 2.3.0.
      // The return type is SourceDirectorySet, which is a Gradle core type always on the classpath.
      val generatedKotlin = sourceSet.javaClass.getMethod("getGeneratedKotlin").invoke(sourceSet)
      generatedKotlin.javaClass.getMethod("srcDir", Any::class.java).invoke(generatedKotlin, generateTask)
    } catch (_: NoSuchMethodException) {
      // generatedKotlin not available on this Kotlin version — kotlin.srcDir() above is sufficient.
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
