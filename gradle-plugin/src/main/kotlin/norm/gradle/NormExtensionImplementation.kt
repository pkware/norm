package norm.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.internal.project.ProjectInternal

internal open class NormExtensionImplementation(project: ProjectInternal) : NormExtension {
  override val databases: NamedDomainObjectContainer<Database> = project.objects.domainObjectContainer(
    Database::class.java,
  )
}
