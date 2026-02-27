package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeSpec

internal const val RUNTIME_PACKAGE = "norm"

internal val COLUMN_ADAPTER = ClassName(RUNTIME_PACKAGE, "ColumnAdapter")
internal val JAKARTA_SINGLETON = ClassName("jakarta.inject", "Singleton")
internal val MICRONAUT_REQUIRES = ClassName("io.micronaut.context.annotation", "Requires")
internal val SPRING_COMPONENT = ClassName("org.springframework.stereotype", "Component")

/**
 * Adds framework-specific DI annotations to an adapter class.
 *
 * Unlike `PostgresQueries`, adapters don't use `@Requires(missingBeans)` because there's no
 * interface to check against — users override adapters via constructor parameter defaults instead.
 */
internal fun addAdapterDependencyInjectionAnnotations(classBuilder: TypeSpec.Builder, frameworks: Set<Framework>) {
  for (framework in frameworks) {
    when (framework) {
      Framework.MICRONAUT_DATA -> classBuilder.addAnnotation(JAKARTA_SINGLETON)
      Framework.SPRING_DATA -> classBuilder.addAnnotation(SPRING_COMPONENT)
    }
  }
}
