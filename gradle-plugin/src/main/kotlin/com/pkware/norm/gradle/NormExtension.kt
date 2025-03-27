package com.pkware.norm.gradle

import com.pkware.norm.gradle.NormPlugin.Companion.NORM_GENERATED_CODE
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Configuration options for Norm.
 */
public interface NormExtension {

  /**
   * Version of [sqlc](https://sqlc.dev) to use.
   */
  public val sqlcVersion: Property<String>

  /**
   * SQL source code.
   */
  public val sqlSources: NamedDomainObjectContainer<SqlSource>
}

public abstract class SqlSource(@get:Input public val name: String) {

  // FIXME Can take directories as well, and I think it can be a list
  /**
   * Path to the file containing the SQL schema.
   *
   * Relative paths will be resolved against the project directory.
   */
  @get:Input
  public abstract val schemaPath: Property<String>

  /**
   * Path to the file containing the queries for which to generate code.
   *
   * Relative paths will be resolved against the project directory.
   */
  @get:Input
  public abstract val queriesPath: Property<String>

  /**
   * Name of the package in which code should be generated.
   */
  @get:Input
  public abstract val packageName: Property<String>

  internal fun generatedPackageDirectory(project: Project) = packageName.flatMap { packageName ->
    project.layout.buildDirectory.dir("$NORM_GENERATED_CODE/${packageName.replace('.', '/')}")
  }

  internal fun configurationFile(project: Project) = project.layout.buildDirectory.file("norm/$name/sqlc.yaml")
}
