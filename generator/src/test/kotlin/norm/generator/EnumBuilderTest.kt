package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.squareup.kotlinpoet.FileSpec
import org.junit.jupiter.api.Test

/**
 * Tests for Kotlin code generation from PostgreSQL enum types.
 *
 * Uses golden file comparison to validate complete generated output structure and formatting.
 */
class EnumBuilderTest {

  @Test
  fun `enum without comment generates correct output`() {
    val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"), comment = "")
    val output = generateEnumCode(moodEnum, "example")

    val expected =
      """
      |package example
      |
      |import kotlin.String
      |
      |/**
      | * @property databaseValue The representation of this enum in Postgres.
      | */
      |public enum class Mood(
      |  public val databaseValue: String,
      |) {
      |  HAPPY("happy"),
      |  SAD("sad"),
      |  ANGRY("angry"),
      |  ;
      |
      |  public companion object {
      |    /**
      |     * @returns the enum constant matching [value], or `null` if no match exists.
      |     */
      |    public fun fromDatabaseValue(`value`: String): Mood? = entries.firstOrNull { it.databaseValue == value }
      |  }
      |}
      |
      """
        .trimMargin()

    assertThat(output).isEqualTo(expected)
  }

  @Test
  fun `enum with single-line comment generates correct output`() {
    val moodEnum =
      Enum(name = "mood", vals = listOf("happy", "sad", "angry"), comment = "Represents emotional state.")
    val output = generateEnumCode(moodEnum, "example")

    val expected =
      """
      |package example
      |
      |import kotlin.String
      |
      |/**
      | * Represents emotional state.
      | *
      | * @property databaseValue The representation of this enum in Postgres.
      | */
      |public enum class Mood(
      |  public val databaseValue: String,
      |) {
      |  HAPPY("happy"),
      |  SAD("sad"),
      |  ANGRY("angry"),
      |  ;
      |
      |  public companion object {
      |    /**
      |     * @returns the enum constant matching [value], or `null` if no match exists.
      |     */
      |    public fun fromDatabaseValue(`value`: String): Mood? = entries.firstOrNull { it.databaseValue == value }
      |  }
      |}
      |
      """
        .trimMargin()

    assertThat(output).isEqualTo(expected)
  }

  @Test
  fun `enum with multiline comment generates correct output`() {
    val comment =
      "Represents the emotional state of a person.\n\nUsed in the users table to track current and historical moods."
    val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"), comment = comment)
    val output = generateEnumCode(moodEnum, "example")

    val expected =
      """
      |package example
      |
      |import kotlin.String
      |
      |/**
      | * Represents the emotional state of a person.
      | *
      | * Used in the users table to track current and historical moods.
      | *
      | * @property databaseValue The representation of this enum in Postgres.
      | */
      |public enum class Mood(
      |  public val databaseValue: String,
      |) {
      |  HAPPY("happy"),
      |  SAD("sad"),
      |  ANGRY("angry"),
      |  ;
      |
      |  public companion object {
      |    /**
      |     * @returns the enum constant matching [value], or `null` if no match exists.
      |     */
      |    public fun fromDatabaseValue(`value`: String): Mood? = entries.firstOrNull { it.databaseValue == value }
      |  }
      |}
      |
      """
        .trimMargin()

    assertThat(output).isEqualTo(expected)
  }

  @Test
  fun `adapter generates correct output`() {
    val moodEnum = Enum(name = "mood", vals = listOf("happy", "sad", "angry"), comment = "Enum comment")
    val output = generateAdapterCode(moodEnum, "example", emptySet())

    val expected =
      """
      |package example
      |
      |import java.lang.IllegalArgumentException
      |import kotlin.String
      |import norm.ColumnAdapter
      |
      |public class MoodAdapter : ColumnAdapter<Mood, String> {
      |  override fun decode(databaseValue: String): Mood = when (databaseValue) {
      |    "happy" -> Mood.HAPPY
      |    "sad" -> Mood.SAD
      |    "angry" -> Mood.ANGRY
      |    else -> throw IllegalArgumentException("Unknown Mood database value: " + databaseValue)
      |  }
      |
      |  override fun encode(`value`: Mood): String = value.databaseValue
      |}
      |
      """
        .trimMargin()

    assertThat(output).isEqualTo(expected)
  }

  private fun generateEnumCode(enumDefinition: Enum, packageName: String): String {
    val typeSpec = buildEnumTypeSpec(enumDefinition, packageName)
    val fileSpec = FileSpec.builder(packageName, "Mood.kt").addType(typeSpec).build()
    return buildString { fileSpec.writeTo(this) }
  }

  private fun generateAdapterCode(enumDefinition: Enum, packageName: String, frameworks: Set<Framework>): String {
    val typeSpec = buildAdapterTypeSpec(enumDefinition, packageName, frameworks)
    val fileSpec = FileSpec.builder(packageName, "MoodAdapter.kt").addType(typeSpec).build()
    return buildString { fileSpec.writeTo(this) }
  }
}
