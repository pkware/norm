package norm.generator

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

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

  val propertyType = domainKotlinPropertyType(domain.baseType)

  val builder = TypeSpec.classBuilder(className.simpleName)
    .addModifiers(KModifier.VALUE)
    .addAnnotation(JvmInline::class)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("value", propertyType)
        .build(),
    )
    .addProperty(
      PropertySpec.builder("value", propertyType)
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
  val wireKotlinType = domainKotlinWireType(domain.baseType)
  val adapterSupertype = COLUMN_ADAPTER.parameterizedBy(valueClassName, wireKotlinType)

  // An array-based domain's value class wraps List<Int?> while the adapter's wire type is
  // Array<Int?> (domainKotlinPropertyType's KDoc), so decode/encode must convert between them —
  // every other domain base type wraps the same Kotlin type on both sides, and a bare
  // `Email(databaseValue)`/`value.value` suffices.
  val isArrayBaseType = domain.baseType.startsWith("_")

  val decodeFunction = FunSpec.builder("decode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("databaseValue", wireKotlinType)
    .returns(valueClassName)
    .addStatement(
      if (isArrayBaseType) "return %T(databaseValue.asList())" else "return %T(databaseValue)",
      valueClassName,
    )

  val encodeFunction = FunSpec.builder("encode")
    .addModifiers(KModifier.OVERRIDE)
    .addParameter("value", valueClassName)
    .returns(wireKotlinType)
    .addStatement(if (isArrayBaseType) "return value.value.toTypedArray()" else "return value.value")

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
 * Maps a Postgres base type name to the Kotlin type JDBC delivers it as — the type a domain's
 * `ColumnAdapter` encodes to and decodes from.
 *
 * Delegates to [resolveWireCodec] as the single source of truth for type mappings. Every type
 * with an entry there is usable as a domain base, including `json` and `jsonb`: the wire type
 * matches the same Kotlin type the plain column would produce, and the binding difference
 * (`setObject(..., Types.OTHER)` rather than `setString`) is carried by the [WireCodec] that
 * [TypeRepository] hands to [AdaptedTypeSqlMappable], not by the wire type itself.
 *
 * [baseTypeName] is always a terminal, non-domain Postgres type (see [Domain.baseType]'s KDoc):
 * stacked domains (`CREATE DOMAIN work_email AS email`) are resolved to their terminal base type
 * before a [Domain] reaches this function, so `baseTypeName` naming another domain never occurs.
 * For a domain over an array type (`CREATE DOMAIN int_set AS int[]`, `baseTypeName` like
 * `"_int4"`), this returns the *wire* type (`Array<Int?>`) — the adapter's `ColumnAdapter` type
 * argument and `decode`/`encode` parameter/return type. It is [domainKotlinPropertyType], not this
 * function, that returns the *property* type the value class wraps (`List<Int?>`); see that
 * function's KDoc for why the two differ.
 *
 * [resolveWireCodec] reads [POSTGRES_BASE_TYPES], the same map [TypeRepository.resolveBaseType]
 * reads for a plain column's type, so [error] here is unreachable for a domain built on any type
 * that map supports — `CREATE DOMAIN d AS timestamptz`/`uuid`/`date`/etc. all resolve, by
 * construction. It stays reachable for a Postgres type Norm has
 * never mapped to Kotlin at all, as a plain column or otherwise (`xml`, `interval`, `money`, ...),
 * and for a domain over an unsupported array base type (`_oid`, an array of an enum, an array of a
 * domain — see [resolveWireCodec]'s KDoc) — though [TypeRepository.tryResolveDomainType] already
 * fails fast with a more specific diagnostic before either case reaches here in the real pipeline.
 * Postgres allows a domain over any of these, so hitting this is expected, not a bug — failing
 * fast with the unsupported type's name beats silently guessing a mapping Norm has no tested
 * behavior for.
 */
internal fun domainKotlinWireType(baseTypeName: String): TypeName =
  resolveWireCodec(baseTypeName)?.kotlinType ?: error("Unsupported domain base type: $baseTypeName")

/**
 * Maps a Postgres base type name to the Kotlin type the domain's *value class* wraps.
 *
 * Equal to [domainKotlinWireType] for every base type except an array type (`baseTypeName` like
 * `"_int4"`, for `CREATE DOMAIN int_set AS int[]`): there, [domainKotlinWireType] returns
 * `Array<Int?>` (the adapter's wire type), but this function returns `List<Int?>` instead.
 * `kotlin.Array` has identity equality, and Norm's generated row types are `@JvmRecord data class`
 * (see `test-scenarios/domains/example/example/Users.kt`), so a value class wrapping `Array` would
 * make row `equals` silently wrong whenever a query result is compared — `List`'s structural
 * equality does not have that problem. The adapter's `decode`/`encode` convert between the two
 * (`Array<Int?>.asList()` / `List<Int?>.toTypedArray()`); see [buildDomainAdapterTypeSpec].
 */
internal fun domainKotlinPropertyType(baseTypeName: String): TypeName {
  val wireType = domainKotlinWireType(baseTypeName)
  return if (wireType is ParameterizedTypeName && wireType.rawType == ARRAY) {
    LIST.parameterizedBy(wireType.typeArguments.single())
  } else {
    wireType
  }
}
