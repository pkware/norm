package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for Kotlin code generation from PostgreSQL domain types.
 *
 * Uses golden file comparison to validate complete generated output structure and formatting.
 */
class DomainBuilderTest {

  @Nested
  inner class ValueClass {

    @Test
    fun `TEXT domain generates correct value class`() {
      val domain = Domain(name = "email", baseType = "text", comment = "")
      val output = generateValueClassCode(domain, "example")

      val expected =
        """
        |package example
        |
        |import kotlin.String
        |import kotlin.jvm.JvmInline
        |
        |/**
        | * @property value The underlying database value.
        | */
        |@JvmInline
        |public value class Email(
        |  public val `value`: String,
        |)
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }

    @Test
    fun `INTEGER domain generates correct value class`() {
      val domain = Domain(name = "positive_integer", baseType = "int4", comment = "")
      val output = generateValueClassCode(domain, "example")

      val expected =
        """
        |package example
        |
        |import kotlin.Int
        |import kotlin.jvm.JvmInline
        |
        |/**
        | * @property value The underlying database value.
        | */
        |@JvmInline
        |public value class PositiveInteger(
        |  public val `value`: Int,
        |)
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }

    @Test
    fun `SMALLINT domain generates correct value class`() {
      val domain = Domain(name = "small_count", baseType = "int2", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Short")
      assertThat(output).contains("public value class SmallCount")
      assertThat(output).contains("public val `value`: Short")
    }

    @Test
    fun `BIGINT domain generates correct value class`() {
      val domain = Domain(name = "big_count", baseType = "int8", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Long")
      assertThat(output).contains("public val `value`: Long")
    }

    @Test
    fun `FLOAT domain generates correct value class`() {
      val domain = Domain(name = "latitude", baseType = "float4", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Float")
      assertThat(output).contains("public val `value`: Float")
    }

    @Test
    fun `DOUBLE domain generates correct value class`() {
      val domain = Domain(name = "longitude", baseType = "float8", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Double")
      assertThat(output).contains("public val `value`: Double")
    }

    @Test
    fun `BOOLEAN domain generates correct value class`() {
      val domain = Domain(name = "active_flag", baseType = "bool", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Boolean")
      assertThat(output).contains("public val `value`: Boolean")
    }

    @Test
    fun `NUMERIC domain generates correct value class`() {
      val domain = Domain(name = "currency_amount", baseType = "numeric", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import java.math.BigDecimal")
      assertThat(output).contains("public val `value`: BigDecimal")
    }

    @Test
    fun `json domain generates a String value class`() {
      // Postgres accepts CREATE DOMAIN d AS json, so json must be usable as a domain base. The
      // Types.OTHER binding lives in the JdbcTypeInfo, not in the wrapped Kotlin type.
      val domain = Domain(name = "json_doc", baseType = "json", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.String")
      assertThat(output).contains("public val `value`: String")
    }

    @Test
    fun `jsonb domain generates a String value class`() {
      val domain = Domain(name = "jsonb_doc", baseType = "jsonb", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("public val `value`: String")
    }

    @Test
    fun `TIMESTAMPTZ domain generates an Instant value class`() {
      // Regression coverage for the build-breaking bug: CREATE DOMAIN d AS timestamptz used to
      // abort code generation entirely -- resolveJdbcTypeInfo had no entry for timestamptz even
      // though it is one of the most common domain base types.
      val domain = Domain(name = "occurred_at", baseType = "timestamptz", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import java.time.Instant")
      assertThat(output).contains("public val `value`: Instant")
    }

    @Test
    fun `UUID domain generates a UUID value class`() {
      val domain = Domain(name = "external_id", baseType = "uuid", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import java.util.UUID")
      assertThat(output).contains("public val `value`: UUID")
    }

    @Test
    fun `DATE domain generates a LocalDate value class`() {
      val domain = Domain(name = "preferred_date", baseType = "date", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import java.time.LocalDate")
      assertThat(output).contains("public val `value`: LocalDate")
    }

    @Test
    fun `BYTEA domain generates a ByteArray value class`() {
      val domain = Domain(name = "thumbnail", baseType = "bytea", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("public val `value`: ByteArray")
    }

    @Test
    fun `unsupported base type throws error`() {
      val exception = assertThrows<IllegalStateException> {
        domainKotlinBaseType("xml")
      }
      assertThat(exception.message!!).contains("Unsupported domain base type")
      assertThat(exception.message!!).contains("xml")
    }

    @Test
    fun `domain with single-line comment generates KDoc`() {
      val domain = Domain(
        name = "email",
        baseType = "text",
        comment = "A valid email address conforming to RFC 5322.",
      )
      val output = generateValueClassCode(domain, "example")

      val expected =
        """
        |package example
        |
        |import kotlin.String
        |import kotlin.jvm.JvmInline
        |
        |/**
        | * A valid email address conforming to RFC 5322.
        | *
        | * @property value The underlying database value.
        | */
        |@JvmInline
        |public value class Email(
        |  public val `value`: String,
        |)
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }

    @Test
    fun `domain with multiline comment generates correct KDoc`() {
      val comment = "A valid email address conforming to RFC 5322.\n\nUsed for user contact information."
      val domain = Domain(name = "email", baseType = "text", comment = comment)
      val output = generateValueClassCode(domain, "example")

      val expected =
        """
        |package example
        |
        |import kotlin.String
        |import kotlin.jvm.JvmInline
        |
        |/**
        | * A valid email address conforming to RFC 5322.
        | *
        | * Used for user contact information.
        | *
        | * @property value The underlying database value.
        | */
        |@JvmInline
        |public value class Email(
        |  public val `value`: String,
        |)
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }
  }

  @Nested
  inner class Adapter {

    @Test
    fun `TEXT domain adapter generates correct output`() {
      val domain = Domain(name = "email", baseType = "text", comment = "")
      val output = generateAdapterCode(domain, "example", emptySet())

      val expected =
        """
        |package example
        |
        |import kotlin.String
        |import norm.ColumnAdapter
        |
        |public class EmailAdapter : ColumnAdapter<Email, String> {
        |  override fun decode(databaseValue: String): Email = Email(databaseValue)
        |
        |  override fun encode(`value`: Email): String = value.value
        |}
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }

    @Test
    fun `INTEGER domain adapter generates correct output`() {
      val domain = Domain(name = "positive_integer", baseType = "int4", comment = "")
      val output = generateAdapterCode(domain, "example", emptySet())

      val expected =
        """
        |package example
        |
        |import kotlin.Int
        |import norm.ColumnAdapter
        |
        |public class PositiveIntegerAdapter : ColumnAdapter<PositiveInteger, Int> {
        |  override fun decode(databaseValue: Int): PositiveInteger = PositiveInteger(databaseValue)
        |
        |  override fun encode(`value`: PositiveInteger): Int = value.value
        |}
        |
        """
          .trimMargin()

      assertThat(output).isEqualTo(expected)
    }

    @Test
    fun `Micronaut framework generates @Singleton annotation`() {
      val domain = Domain(name = "email", baseType = "text", comment = "")
      val output = generateAdapterCode(domain, "example", setOf(Framework.MICRONAUT_DATA))
      assertThat(output).contains("@Singleton")
      assertThat(output).contains("import jakarta.inject.Singleton")
    }

    @Test
    fun `Spring framework generates @Component annotation`() {
      val domain = Domain(name = "email", baseType = "text", comment = "")
      val output = generateAdapterCode(domain, "example", setOf(Framework.SPRING_DATA))
      assertThat(output).contains("@Component")
      assertThat(output).contains("import org.springframework.stereotype.Component")
    }
  }

  private fun generateValueClassCode(domain: Domain, packageName: String): String {
    val typeSpec = buildDomainValueClassTypeSpec(domain, packageName)
    val fileSpec = FileSpec.builder(packageName, "${typeSpec.name}.kt").addType(typeSpec).build()
    return buildString { fileSpec.writeTo(this) }
  }

  private fun generateAdapterCode(domain: Domain, packageName: String, frameworks: Set<Framework>): String {
    val typeSpec = buildDomainAdapterTypeSpec(domain, packageName, frameworks)
    val fileSpec = FileSpec.builder(packageName, "${typeSpec.name}.kt").addType(typeSpec).build()
    return buildString { fileSpec.writeTo(this) }
  }
}
