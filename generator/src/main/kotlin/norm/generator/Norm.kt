package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeSpec

internal const val RUNTIME_PACKAGE = "norm"

internal val COLUMN_ADAPTER = ClassName(RUNTIME_PACKAGE, "ColumnAdapter")
internal val JAKARTA_SINGLETON = ClassName("jakarta.inject", "Singleton")
internal val MICRONAUT_REQUIRES = ClassName("io.micronaut.context.annotation", "Requires")
internal val JAVAX_DATASOURCE = ClassName("javax.sql", "DataSource")
internal val SPRING_COMPONENT = ClassName("org.springframework.stereotype", "Component")

/**
 * Adds each framework's [Framework.adapterAnnotations] to an adapter class.
 *
 * Users override adapters through constructor parameter defaults, so adapters need no `@Requires(missingBeans)`.
 */
internal fun addAdapterDependencyInjectionAnnotations(classBuilder: TypeSpec.Builder, frameworks: Set<Framework>) {
  frameworks.flatMap { it.adapterAnnotations }.forEach { classBuilder.addAnnotation(it) }
}
