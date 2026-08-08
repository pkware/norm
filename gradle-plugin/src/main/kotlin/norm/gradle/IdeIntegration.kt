package norm.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * IDE integration utilities for improving the developer experience when using Norm with IntelliJ IDEA.
 *
 * Provides two mechanisms:
 * 1. Registers generated sources via Kotlin's `generatedKotlin` API (Kotlin 2.3.0+) when available. As of
 *    IntelliJ 2026.1, this marks the directory as a generated source root (YouTrack KTIJ-35910, Fixed). It
 *    does NOT, by itself, cause IntelliJ to run generation during sync — that is tracked separately as
 *    KTIJ-31282, which is Planned with no fix version at time of writing.
 * 2. Wires Norm's generation tasks as dependencies of [PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME], a task Norm
 *    registers under its own name and that IntelliJ runs on every Gradle sync (including first import), so
 *    generated sources exist by the time the IDE reads them.
 */
internal object IdeIntegration {

  /**
   * Name of the task Norm registers so IntelliJ IDEA runs Norm's generation tasks on every Gradle sync.
   *
   * IMPORTANT: IntelliJ's `PrepareKotlinIdeaImportTaskModelBuilder` (Kotlin Gradle Plugin's IDE tooling,
   * bundled with the IDE) discovers sync tasks by scanning `project.tasks.getNames()` for any name that
   * STARTS WITH `prepareKotlinIdeaImport`, and requests every match — a prefix match, not an exact-name
   * match (verified by decompiling the IDE's own `kotlin-gradle-tooling.jar`). This name is therefore
   * load-bearing: it must keep the `prepareKotlinIdeaImport` prefix, or IntelliJ silently stops finding it
   * — generated sources simply stop being refreshed on sync, with no build failure and no warning. Do NOT
   * rename this constant to a value that does not start with `prepareKotlinIdeaImport`.
   *
   * The name deliberately does not equal Kotlin Gradle Plugin's own `prepareKotlinIdeaImport` task name:
   * Norm always registers this task under a name it owns outright, so it can never collide with a task a
   * consumer, or Kotlin Gradle Plugin itself, registers under that exact name, regardless of when they do
   * so (including from their own `afterEvaluate` block, which runs after Norm's plugin has applied).
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
   * Registers [PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME] on [project], the task IntelliJ IDEA discovers and
   * runs on every Gradle sync (see that constant's KDoc for why the name matters). The task depends on
   * [generateTasks] whenever [generateOnIdeSync] resolves to `true`, so generated sources exist before the
   * IDE reads them.
   *
   * This function only ever reads or mutates [project]'s own `tasks` container: it never reaches into
   * `project.rootProject` or any other project, so it is unconditionally safe under Gradle's Isolated
   * Projects, including when [project] is a subproject.
   *
   * The task is always registered, even when [generateOnIdeSync] resolves to `false` — with a name Norm
   * owns outright, registering an empty placeholder task is harmless, and it keeps this function's
   * behavior independent of when, in the build script, `generateOnIdeSync` is set. The dependency itself
   * is computed from [generateOnIdeSync] via [Provider.map], so it is resolved lazily at task-graph time,
   * after the whole build script — including a `norm { databases { } }` block appearing either before or
   * after `generateOnIdeSync` is set — has finished configuring. No `afterEvaluate` is needed.
   */
  fun configureGenerationOnIdeSync(
    project: Project,
    generateOnIdeSync: Provider<Boolean>,
    generateTasks: List<TaskProvider<NormGenerateTask>>,
  ) {
    project.tasks.register(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME) {
      description = "Runs Norm's generation tasks so generated sources exist before IntelliJ IDEA reads " +
        "them. IntelliJ IDEA runs this task, and any other task whose name starts with " +
        "`prepareKotlinIdeaImport`, on every Gradle sync, including first import."
      dependsOn(
        generateOnIdeSync.map { enabled ->
          if (enabled) generateTasks else emptyList()
        },
      )
    }
  }
}
