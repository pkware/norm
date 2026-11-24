package norm.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import norm.generator.Domain as DomainDefinition
import norm.generator.Enum as EnumDefinition

/**
 * [WireCodec] for Postgres enum types.
 *
 * Enum types require `setObject(index, value, Types.OTHER)`.
 * The Postgres JDBC driver rejects `VARCHAR` bindings for enum columns in prepared statements;
 * `Types.OTHER` bypasses driver-side type enforcement and lets Postgres coerce the string. This is
 * the same codec instance the `json`/`jsonb` rows of [POSTGRES_BASE_TYPES] use, so the binding for
 * a plain `json`/`jsonb` column and an enum column can never drift apart.
 */
internal val ENUM_CODEC: WireCodec = POSTGRES_BASE_TYPES.getValue("json").codec

/**
 * A Postgres enum or domain that Norm generates a Kotlin type and `ColumnAdapter` for.
 *
 * Two types share an [adapterPropertyName] when their Postgres names differ only in ways that
 * camel-casing erases, such as `foo_bar` and `fooBar`.
 */
internal sealed class GeneratedAdapterType {

  /** Package of the generated classes. */
  abstract val packageName: String

  /** The Postgres type name. */
  abstract val postgresName: String

  /** Names the Postgres type and its kind in error messages, for example `enum "mood"`. */
  abstract val description: String

  /** Kotlin type generated for the Postgres type: an enum class or a value class. */
  val className: ClassName
    get() = ClassName(packageName, postgresName.snakeToCamelCase().titleCase())

  /** The generated `ColumnAdapter` implementation. */
  val adapterClassName: ClassName
    get() = ClassName(packageName, "${className.simpleName}Adapter")

  /** Name of the adapter's constructor parameter and property on `PostgresQueries`. */
  val adapterPropertyName: String
    get() = "${postgresName.snakeToCamelCase()}Adapter"

  /** The Kotlin type JDBC delivers the Postgres type as, which the adapter encodes to and decodes from. */
  abstract val wireType: TypeName

  /**
   * Builds the generated Kotlin type followed by its adapter class.
   *
   * @param frameworks The DI frameworks to annotate the adapter with.
   * @throws IllegalStateException if the type's wire type is unsupported.
   */
  @Throws(IllegalStateException::class)
  abstract fun typeSpecs(frameworks: Set<Framework>): List<TypeSpec>

  /** A Postgres enum, generated as a Kotlin `enum class` carried over JDBC as a string. */
  data class Enum(val definition: EnumDefinition, override val packageName: String) : GeneratedAdapterType() {
    override val postgresName: String
      get() = definition.name

    override val description: String
      get() = """enum "$postgresName""""

    override val wireType: TypeName
      get() = ENUM_CODEC.kotlinType

    override fun typeSpecs(frameworks: Set<Framework>): List<TypeSpec> =
      listOf(buildEnumTypeSpec(this), buildAdapterTypeSpec(this, frameworks))
  }

  /** A Postgres domain, generated as a `@JvmInline value class` over its base type. */
  data class Domain(val definition: DomainDefinition, override val packageName: String) : GeneratedAdapterType() {
    override val postgresName: String
      get() = definition.name

    override val description: String
      get() = """domain "$postgresName""""

    override val wireType: TypeName
      get() = domainKotlinWireType(definition.baseType)

    override fun typeSpecs(frameworks: Set<Framework>): List<TypeSpec> =
      listOf(buildDomainValueClassTypeSpec(this), buildDomainAdapterTypeSpec(this, frameworks))
  }
}
