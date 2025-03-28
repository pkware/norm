package com.pkware.norm.gradle

import norm.gradle.Database
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

internal open class NormExtensionImplementation(
  project: ProjectInternal,
) : NormExtension {
  override val sqlcVersion: Property<String> = project.objects.property<String>().convention("1.25.0")
  override val databases: NamedDomainObjectContainer<Database> = project.objects.domainObjectContainer(
    Database::class.java,
  )
}
