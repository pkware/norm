package norm.generator

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

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
 * @param enumType The enum to generate a class for.
 */
internal fun buildEnumTypeSpec(enumType: GeneratedAdapterType.Enum): TypeSpec {
  val enumDefinition = enumType.definition
  val enumClassName = enumType.className

  val enumBuilder = TypeSpec.enumBuilder(enumClassName)
    // Passed as a "%L" argument so KotlinPoet treats a "%" in the comment as text.
    .apply { if (enumDefinition.comment.isNotEmpty()) addKdoc("%L\n\n", enumDefinition.comment) }
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
 * @param enumType The enum to generate an adapter for.
 * @param frameworks The DI frameworks to annotate the adapter with.
 */
internal fun buildAdapterTypeSpec(enumType: GeneratedAdapterType.Enum, frameworks: Set<Framework>): TypeSpec {
  val enumDefinition = enumType.definition
  val enumClassName = enumType.className
  val adapterClassName = enumType.adapterClassName
  val adapterSupertype = COLUMN_ADAPTER.parameterizedBy(enumClassName, enumType.wireType)

  val decodeFunction = FunSpec.builder("decode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("databaseValue", enumType.wireType)
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
    .returns(enumType.wireType)
    .addStatement("return value.databaseValue")

  val classBuilder = TypeSpec.classBuilder(adapterClassName)
    .addSuperinterface(adapterSupertype)
    .addFunction(decodeFunction.build())
    .addFunction(encodeFunction.build())

  addAdapterDependencyInjectionAnnotations(classBuilder, frameworks)

  return classBuilder.build()
}
