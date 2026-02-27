package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import plugin.Domain
import java.math.BigDecimal

/**
 * Builds a `@JvmInline value class` [TypeSpec] from a Postgres domain type definition.
 *
 * Generates:
 * ```kotlin
 * @JvmInline
 * value class Email(val value: String)
 * ```
 *
 * @param domain The Postgres domain definition from the catalog.
 * @param packageName The package for the generated value class.
 */
internal fun buildDomainValueClassTypeSpec(domain: Domain, packageName: String): TypeSpec {
  val className = domainValueClassName(domain, packageName)

  val builder = TypeSpec.classBuilder(className.simpleName)
    .addModifiers(KModifier.VALUE)
    .addAnnotation(JvmInline::class)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("value", domainKotlinBaseType(domain.base_type))
        .build(),
    )
    .addProperty(
      PropertySpec.builder("value", domainKotlinBaseType(domain.base_type))
        .initializer("value")
        .build(),
    )

  val kdoc = buildString {
    if (domain.comment.isNotEmpty()) {
      append(domain.comment)
      append("\n\n")
    }
    append("@property value The underlying database value.")
  }
  builder.addKdoc("%L", kdoc)

  return builder.build()
}

/**
 * Builds a `ColumnAdapter` implementation [TypeSpec] for a Postgres domain type.
 *
 * Generates:
 * ```kotlin
 * class EmailAdapter : ColumnAdapter<Email, String> {
 *   override fun decode(databaseValue: String): Email = Email(databaseValue)
 *   override fun encode(value: Email): String = value.value
 * }
 * ```
 *
 * @param domain The Postgres domain definition from the catalog.
 * @param packageName The package for the generated adapter class.
 * @param frameworks The DI frameworks to annotate the adapter with.
 */
internal fun buildDomainAdapterTypeSpec(domain: Domain, packageName: String, frameworks: Set<Framework>): TypeSpec {
  val valueClassName = domainValueClassName(domain, packageName)
  val adapterClass = domainAdapterClassName(domain, packageName)
  val baseKotlinType = domainKotlinBaseType(domain.base_type)
  val adapterSupertype = COLUMN_ADAPTER.parameterizedBy(valueClassName, baseKotlinType)

  val decodeFunction = FunSpec.builder("decode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("databaseValue", baseKotlinType)
    .returns(valueClassName)
    .addStatement("return %T(databaseValue)", valueClassName)

  val encodeFunction = FunSpec.builder("encode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("value", valueClassName)
    .returns(baseKotlinType)
    .addStatement("return value.value")

  val classBuilder = TypeSpec.classBuilder(adapterClass)
    .addSuperinterface(adapterSupertype)
    .addFunction(decodeFunction.build())
    .addFunction(encodeFunction.build())

  addAdapterDependencyInjectionAnnotations(classBuilder, frameworks)

  return classBuilder.build()
}

/**
 * Returns the [ClassName] for the value class generated for a Postgres domain type.
 *
 * @param domain The Postgres domain definition.
 * @param packageName The package in which the value class is generated.
 */
internal fun domainValueClassName(domain: Domain, packageName: String): ClassName =
  ClassName(packageName, domain.name.snakeToCamelCase().titleCase())

/**
 * Returns the [ClassName] for the adapter class generated for a Postgres domain type.
 *
 * @param domain The Postgres domain definition.
 * @param packageName The package in which the adapter is generated.
 */
internal fun domainAdapterClassName(domain: Domain, packageName: String): ClassName =
  ClassName(packageName, "${domain.name.snakeToCamelCase().titleCase()}Adapter")

/**
 * Returns the adapter property name used on `PostgresQueries` for the given domain type.
 *
 * Called from both [TypeRepository] (when creating [AdaptedTypeSqlMappable]) and [Main][generateCode]
 * (when adding constructor parameters to `PostgresQueries`). Centralizing the convention here
 * ensures the generated read/write code always references the same property name.
 */
internal fun domainAdapterPropertyName(domain: Domain): String = "${domain.name.snakeToCamelCase()}Adapter"

/**
 * Maps a Postgres base type name to the corresponding Kotlin [TypeName].
 *
 * This mapping must stay in sync with [resolveJdbcTypeInfo] — both functions need to
 * agree on which Postgres types are supported as domain bases.
 */
internal fun domainKotlinBaseType(baseTypeName: String): TypeName = when (baseTypeName) {
  "text", "varchar", "bpchar" -> String::class.asTypeName()
  "int2" -> Short::class.asTypeName()
  "int4" -> Int::class.asTypeName()
  "int8" -> Long::class.asTypeName()
  "float4" -> Float::class.asTypeName()
  "float8" -> Double::class.asTypeName()
  "bool" -> Boolean::class.asTypeName()
  "numeric" -> BigDecimal::class.asTypeName()
  else -> error("Unsupported domain base type: $baseTypeName")
}
