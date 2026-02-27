package norm.generator

import com.squareup.kotlinpoet.ClassName

internal const val RUNTIME_PACKAGE = "norm"

internal val COLUMN_ADAPTER = ClassName(RUNTIME_PACKAGE, "ColumnAdapter")
internal val JAKARTA_SINGLETON = ClassName("jakarta.inject", "Singleton")
internal val MICRONAUT_REQUIRES = ClassName("io.micronaut.context.annotation", "Requires")
internal val SPRING_COMPONENT = ClassName("org.springframework.stereotype", "Component")
