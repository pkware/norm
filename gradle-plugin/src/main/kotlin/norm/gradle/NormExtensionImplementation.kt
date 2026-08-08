package norm.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.Property

internal open class NormExtensionImplementation(project: ProjectInternal) : NormExtension {
  override val databases: NamedDomainObjectContainer<Database> = project.objects.domainObjectContainer(
    Database::class.java,
  )

  override val generateOnIdeSync: Property<Boolean> = project.objects.property(Boolean::class.java).convention(true)
}
