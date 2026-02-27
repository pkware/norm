package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import plugin.Enum

/**
 * Builds a Kotlin enum class [TypeSpec] from a Postgres enum type definition.
 *
 * Generates:
 * ```kotlin
 * enum class Mood(val databaseValue: String) {
 *   HAPPY("happy"), SAD("sad"), ANGRY("angry");
 *   companion object {
 *     fun fromDatabaseValue(value: String): Mood? =
 *       entries.firstOrNull { it.databaseValue == value }
 *   }
 * }
 * ```
 *
 * @param enumDefinition The Postgres enum definition from the catalog.
 * @param packageName The package for the generated enum class.
 */
internal fun buildEnumTypeSpec(enumDefinition: Enum, packageName: String): TypeSpec {
  val enumClassName = ClassName(packageName, enumDefinition.name.snakeToCamelCase().titleCase())

  val enumBuilder = TypeSpec.enumBuilder(enumClassName)
    // Add KDoc to the enum class if a comment is present.
    .apply { if (enumDefinition.comment.isNotEmpty()) addKdoc("${enumDefinition.comment}\n\n") }
    .addKdoc("@property databaseValue The representation of this enum in Postgres.")
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("databaseValue", String::class)
        .build(),
    )
    .addProperty(
      PropertySpec.builder("databaseValue", String::class)
        .initializer("databaseValue")
        .build(),
    )

  for (label in enumDefinition.vals) {
    enumBuilder.addEnumConstant(
      label.toUpperSnakeCase(),
      TypeSpec.anonymousClassBuilder()
        .addSuperclassConstructorParameter("%S", label)
        .build(),
    )
  }

  enumBuilder.addType(
    TypeSpec.companionObjectBuilder()
      .addFunction(
        FunSpec.builder("fromDatabaseValue")
          .addKdoc(
            "@returns the enum constant matching [value], or `null` if no match exists.",
          )
          .addParameter("value", String::class)
          .returns(enumClassName.copy(nullable = true))
          .addStatement("return entries.firstOrNull { it.databaseValue == value }")
          .build(),
      )
      .build(),
  )

  return enumBuilder.build()
}

/**
 * Builds a `ColumnAdapter` implementation [TypeSpec] for a Postgres enum type.
 *
 * Generates:
 * ```kotlin
 * class MoodAdapter : ColumnAdapter<Mood, String> {
 *   override fun decode(databaseValue: String): Mood = when (databaseValue) {
 *     "happy" -> Mood.HAPPY
 *     "sad" -> Mood.SAD
 *     else -> throw IllegalArgumentException("Unknown Mood database value: $databaseValue")
 *   }
 *   override fun encode(value: Mood): String = value.databaseValue
 * }
 * ```
 *
 * @param enumDefinition The Postgres enum definition from the catalog.
 * @param packageName The package for the generated adapter class.
 * @param frameworks The DI frameworks to annotate the adapter with.
 */
internal fun buildAdapterTypeSpec(enumDefinition: Enum, packageName: String, frameworks: Set<Framework>): TypeSpec {
  val enumClassName = ClassName(packageName, enumDefinition.name.snakeToCamelCase().titleCase())
  val adapterClassName = ClassName(packageName, "${enumDefinition.name.snakeToCamelCase().titleCase()}Adapter")
  val adapterSupertype = COLUMN_ADAPTER.parameterizedBy(enumClassName, String::class.asTypeName())

  val decodeFunction = FunSpec.builder("decode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("databaseValue", String::class)
    .returns(enumClassName)
    .beginControlFlow("return when (databaseValue)")
  for (label in enumDefinition.vals) {
    decodeFunction.addStatement("%S -> %T.%N", label, enumClassName, label.toUpperSnakeCase())
  }
  decodeFunction.addStatement(
    "else -> throw %T(%S + databaseValue)",
    IllegalArgumentException::class,
    "Unknown ${enumClassName.simpleName} database value: ",
  )
  decodeFunction.endControlFlow()

  val encodeFunction = FunSpec.builder("encode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("value", enumClassName)
    .returns(String::class)
    .addStatement("return value.databaseValue")

  val classBuilder = TypeSpec.classBuilder(adapterClassName)
    .addSuperinterface(adapterSupertype)
    .addFunction(decodeFunction.build())
    .addFunction(encodeFunction.build())

  addAdapterDependencyInjectionAnnotations(classBuilder, frameworks)

  return classBuilder.build()
}

/**
 * Returns the [ClassName] for the adapter class generated for a Postgres enum type.
 *
 * @param enumDefinition The Postgres enum definition.
 * @param packageName The package in which the adapter is generated.
 */
internal fun adapterClassName(enumDefinition: Enum, packageName: String): ClassName =
  ClassName(packageName, "${enumDefinition.name.snakeToCamelCase().titleCase()}Adapter")

/**
 * Returns the adapter property name used on `PostgresQueries` for the given enum type.
 *
 * Called from both [TypeRepository] (when creating [AdaptedTypeSqlMappable]) and [Main][generateCode]
 * (when adding constructor parameters to `PostgresQueries`). Centralizing the convention here
 * ensures the generated read/write code always references the same property name.
 */
internal fun adapterPropertyName(enumDefinition: Enum): String = "${enumDefinition.name.snakeToCamelCase()}Adapter"
