package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.squareup.kotlinpoet.FileSpec
import okio.Buffer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import plugin.Domain

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
      val domain = Domain(name = "email", base_type = "text", comment = "")
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
      val domain = Domain(name = "positive_integer", base_type = "int4", comment = "")
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
      val domain = Domain(name = "small_count", base_type = "int2", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Short")
      assertThat(output).contains("public value class SmallCount")
      assertThat(output).contains("public val `value`: Short")
    }

    @Test
    fun `BIGINT domain generates correct value class`() {
      val domain = Domain(name = "big_count", base_type = "int8", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Long")
      assertThat(output).contains("public val `value`: Long")
    }

    @Test
    fun `FLOAT domain generates correct value class`() {
      val domain = Domain(name = "latitude", base_type = "float4", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Float")
      assertThat(output).contains("public val `value`: Float")
    }

    @Test
    fun `DOUBLE domain generates correct value class`() {
      val domain = Domain(name = "longitude", base_type = "float8", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Double")
      assertThat(output).contains("public val `value`: Double")
    }

    @Test
    fun `BOOLEAN domain generates correct value class`() {
      val domain = Domain(name = "active_flag", base_type = "bool", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import kotlin.Boolean")
      assertThat(output).contains("public val `value`: Boolean")
    }

    @Test
    fun `NUMERIC domain generates correct value class`() {
      val domain = Domain(name = "currency_amount", base_type = "numeric", comment = "")
      val output = generateValueClassCode(domain, "example")
      assertThat(output).contains("import java.math.BigDecimal")
      assertThat(output).contains("public val `value`: BigDecimal")
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
        base_type = "text",
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
      val domain = Domain(name = "email", base_type = "text", comment = comment)
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
      val domain = Domain(name = "email", base_type = "text", comment = "")
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
      val domain = Domain(name = "positive_integer", base_type = "int4", comment = "")
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
      val domain = Domain(name = "email", base_type = "text", comment = "")
      val output = generateAdapterCode(domain, "example", setOf(Framework.MICRONAUT_DATA))
      assertThat(output).contains("@Singleton")
      assertThat(output).contains("import jakarta.inject.Singleton")
    }

    @Test
    fun `Spring framework generates @Component annotation`() {
      val domain = Domain(name = "email", base_type = "text", comment = "")
      val output = generateAdapterCode(domain, "example", setOf(Framework.SPRING_DATA))
      assertThat(output).contains("@Component")
      assertThat(output).contains("import org.springframework.stereotype.Component")
    }
  }

  private fun generateValueClassCode(domain: Domain, packageName: String): String {
    val typeSpec = buildDomainValueClassTypeSpec(domain, packageName)
    val fileSpec = FileSpec.builder(packageName, "${typeSpec.name}.kt").addType(typeSpec).build()
    val output = Buffer()
    output.outputStream().writer().use(fileSpec::writeTo)
    return output.readUtf8()
  }

  private fun generateAdapterCode(domain: Domain, packageName: String, frameworks: Set<Framework>): String {
    val typeSpec = buildDomainAdapterTypeSpec(domain, packageName, frameworks)
    val fileSpec = FileSpec.builder(packageName, "${typeSpec.name}.kt").addType(typeSpec).build()
    Buffer().use { output ->
      output.outputStream().writer().use(fileSpec::writeTo)
      return output.readUtf8()
    }
  }
}
