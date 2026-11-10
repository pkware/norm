package com.pkware.gradle

import com.dylibso.chicory.build.time.compiler.Config
import com.dylibso.chicory.build.time.compiler.Generator
import com.dylibso.chicory.compiler.InterpreterFallback
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Compiles a WebAssembly module to JVM bytecode with Chicory's build-time compiler.
 *
 * The output directory receives `<className>Machine*.class` and `<className>.meta`, the module with every compiled
 * function body stripped. Functions too large for the JVM stay in the `.meta` and run on Chicory's interpreter.
 */
@CacheableTask
public abstract class CompileWasmModule @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
  /** The module to compile. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  public abstract val wasmFile: RegularFileProperty

  /** Fully qualified name of the generated class family, such as `norm.generator.libpgquery.LibPgQuery`. */
  @get:Input
  public abstract val className: Property<String>

  /** Classpath holding `com.dylibso.chicory:build-time-compiler` and its dependencies. */
  @get:Classpath
  public abstract val compilerClasspath: ConfigurableFileCollection

  /** Receives the generated classes and the `.meta` module. */
  @get:OutputDirectory
  public abstract val outputDirectory: DirectoryProperty

  @TaskAction
  public fun compile() {
    workers.classLoaderIsolation { classpath.from(compilerClasspath) }.submit(CompileAction::class.java) {
      wasmFile.set(this@CompileWasmModule.wasmFile)
      className.set(this@CompileWasmModule.className)
      outputDirectory.set(this@CompileWasmModule.outputDirectory)
    }
  }

  internal interface CompileParameters : WorkParameters {
    val wasmFile: RegularFileProperty
    val className: Property<String>
    val outputDirectory: DirectoryProperty
  }

  internal abstract class CompileAction : WorkAction<CompileParameters> {
    override fun execute() {
      val output = parameters.outputDirectory.get().asFile.toPath()
      output.toFile().deleteRecursively()
      val config = Config.builder()
        .withWasmFile(parameters.wasmFile.get().asFile.toPath())
        .withName(parameters.className.get())
        .withTargetClassFolder(output)
        .withTargetSourceFolder(output)
        .withTargetWasmFolder(output)
        .withInterpreterFallback(InterpreterFallback.SILENT)
        .build()
      val generator = Generator(config)
      generator.generateMetaWasm(generator.generateResources())
    }
  }
}
