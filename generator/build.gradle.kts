import com.pkware.gradle.CompileWasmModule

plugins {
  `kotlin-conventions`
  `java-library`
  `publish-conventions`
}

val wasmCompiler by configurations.registering {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  wasmCompiler(libs.chicory.buildTimeCompiler)

  implementation(libs.chicory.runtime)
  implementation(libs.chicory.wasi)
  implementation(libs.kotlinPoet)

  testImplementation(libs.bundles.mockito)
  testImplementation(libs.bundles.testcontainers)
  testImplementation(libs.postgresql)
  testImplementation(libs.commonmark)
}

kotlin {
  explicitApi()
}

val compileLibPgQuery by tasks.registering(CompileWasmModule::class) {
  wasmFile = layout.projectDirectory.file("src/main/resources/libpg_query.wasm")
  className = "norm.generator.libpgquery.LibPgQuery"
  compilerClasspath.from(wasmCompiler)
  outputDirectory = layout.buildDirectory.dir("generated/wasm/libpgquery")
}

sourceSets.main {
  output.dir(compileLibPgQuery.flatMap { it.outputDirectory })
}

dependencies {
  compileOnly(files(compileLibPgQuery.flatMap { it.outputDirectory }).builtBy(compileLibPgQuery))
}

tasks.processResources {
  exclude("libpg_query.wasm")
}

tasks.test {
  useJUnitPlatform {
    excludeTags("generateScannerBaseline")
  }
}

tasks.register<Test>("generateScannerBaseline") {
  description = "Rewrites the committed scanner baseline and the grammar sweep skip list"
  group = "verification"

  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath

  useJUnitPlatform {
    includeTags("generateScannerBaseline")
  }

  // The task writes into the source tree, so a cached or up-to-date result would leave the files stale.
  outputs.upToDateWhen { false }
  outputs.doNotCacheIf("it writes into the source tree") { true }
}
