package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.squareup.kotlinpoet.TypeSpec
import org.junit.jupiter.api.Test

/**
 * A CRUD-synthesized INSERT's overridable-default column bind position must
 * be computed into an immutable `val` before [SqlMappable.statementAction] renders it. The nullable-array
 * and adapted-type implementations (SqlMappable.kt) render the `index` argument TWICE in their bind
 * expression, so a mutating expression passed as `index` (e.g. a bare `nextParameterIndex++`) would
 * double-increment and silently bind the wrong JDBC position. The `author` scenario table
 * (test-scenarios/crud_generation) never exercises this because its own overridable-default column
 * (`created_at`) is a plain scalar, not one of the double-render shapes.
 */
class ImplementationBuilderOptionalColumnTest {

  @Test
  fun `single-row insert computes a nullable array column's bind index once, into a val, before binding`() {
    val statement = createStatement(
      sql = "INSERT INTO t (name, tags) VALUES (?, ?) RETURNING id",
      cmd = ":one",
      params = listOf(
        Parameter(1, column("name", type = "text")),
        Parameter(2, column("tags", type = "text", isArray = true, notNull = false)),
      ),
      columns = listOf(column("id", type = "int4")),
      isSynthesizedInsert = true,
      overridableDefaultParameterPositions = setOf(2),
    )

    val builder = TypeSpec.classBuilder("Test")
    builder.addSqlStatementImplementationMethod(statement)
    val body = builder.build().funSpecs.joinToString("\n") { it.body.toString() }

    // The bind position is computed once into `val tagsIndex`, then referenced by name everywhere
    // the array codec's nullable branch renders `index` -- never by re-evaluating the mutating
    // counter expression itself, which would double-increment on the second render.
    assertThat(body).contains("val tagsIndex = nextParameterIndex")
    assertThat(body).contains("setArray(tagsIndex")
    assertThat(body).contains("setNull(tagsIndex")
    assertThat(body).doesNotContain("setArray(nextParameterIndex")
    assertThat(body).doesNotContain("setNull(nextParameterIndex")
    assertThat(body).doesNotContain("nextParameterIndex++")
  }

  @Test
  fun `batch insert computes a nullable array column's bind index once per call, into a val, before the row loop`() {
    val statement = createStatement(
      sql = "INSERT INTO t (name, tags) VALUES (?, ?) RETURNING id",
      cmd = ":one",
      params = listOf(
        Parameter(1, column("name", type = "text")),
        Parameter(2, column("tags", type = "text", isArray = true, notNull = false)),
      ),
      columns = listOf(column("id", type = "int4")),
      isSynthesizedInsert = true,
      overridableDefaultParameterPositions = setOf(2),
    )

    val builder = TypeSpec.classBuilder("Test")
    builder.addSqlStatementImplementationMethod(statement)
    val body = builder.build().funSpecs.joinToString("\n") { it.body.toString() }

    // The index is computed once per batch call (outside the `for (entry in stream)` loop), from
    // whether the extractor argument is null -- not per row.
    assertThat(body).contains("val tagsIndex: kotlin.Int? = if (tags != null)")
    assertThat(body).contains("setArray(tagsIndex")
    assertThat(body).contains("setNull(tagsIndex")
    assertThat(body).doesNotContain("setArray(nextParameterIndex")
    assertThat(body).doesNotContain("setNull(nextParameterIndex")
  }
}
