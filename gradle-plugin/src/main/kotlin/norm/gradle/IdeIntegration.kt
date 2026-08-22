package norm.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * IDE integration utilities for improving the developer experience when using Norm with IntelliJ IDEA.
 *
 * Registers generated sources so compilation (including KSP) and, on Kotlin 2.3.0+, IntelliJ's
 * source-root detection (YouTrack KTIJ-35910) see them; and wires Norm's generation tasks to run on
 * Gradle sync via [PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME], since Kotlin Gradle Plugin's own sync hook
 * (KTIJ-31282) does not trigger generation by itself.
 */
internal object IdeIntegration {

  /**
   * Name of the task Norm registers so IntelliJ IDEA runs Norm's generation tasks on every Gradle sync.
   *
   * IntelliJ's Kotlin Gradle Plugin tooling discovers sync tasks by name *prefix* `prepareKotlinIdeaImport`,
   * not exact match, so this constant's value must keep that prefix — pinned by
   * [IdeSyncIntegrationTest.TaskNamePrefixContract]. The name intentionally differs from Kotlin Gradle
   * Plugin's own `prepareKotlinIdeaImport` task, so Norm's task can never collide with one a consumer, or
   * Kotlin Gradle Plugin itself, registers under that exact name.
   */
  internal const val PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME = "prepareKotlinIdeaImportNorm"

  /**
   * Registers the output of [generateTask] as a source directory on [sourceSet].
   *
   * Always registers via `KotlinSourceSet.kotlin.srcDir()` so that all compilation tasks — including
   * KSP's `kspKotlin` — receive the generated sources and the correct task dependency is established.
   * KSP reads from `kotlin.srcDirs`, not from `generatedKotlin`.
   *
   * On Kotlin 2.3.0+, also registers via `KotlinSourceSet.generatedKotlin` so IntelliJ 2026.1+ marks the
   * directory as a generated source root (YouTrack KTIJ-35910). This does not, by itself, trigger
   * generation during sync — see the class KDoc — that is handled separately by
   * [configureGenerationOnIdeSync]. IntelliJ deduplicates source roots by path, so the directory appears
   * once either way.
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
   * Registers [PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME] on [project] and makes it depend on every
   * [NormGenerateTask] registered on [project] whenever [generateOnIdeSync] resolves to `true`. The
   * dependency is computed lazily, inside a [Provider.map] against `project.tasks.withType`, so it always
   * reflects every `normGenerate<Name>` task registered by `norm { databases { } }` regardless of whether
   * that block runs before or after this function is called — no fixed list of tasks is captured.
   *
   * Reads only [project]'s own `tasks` container, so it is safe under Gradle's Isolated Projects even when
   * [project] is a subproject; see [IdeSyncIntegrationTest.SubprojectUnderIsolatedProjects].
   */
  fun configureGenerationOnIdeSync(project: Project, generateOnIdeSync: Provider<Boolean>) {
    project.tasks.register(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME) {
      description = "Runs Norm's generation tasks so generated sources exist before IntelliJ IDEA reads " +
        "them. IntelliJ IDEA runs this task, and any other task whose name starts with " +
        "`prepareKotlinIdeaImport`, on every Gradle sync, including first import."
      dependsOn(
        generateOnIdeSync.map { enabled ->
          if (enabled) project.tasks.withType(NormGenerateTask::class.java) else emptyList<Any>()
        },
      )
    }
  }
}
