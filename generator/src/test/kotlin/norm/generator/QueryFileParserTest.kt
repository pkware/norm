package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class QueryFileParserTest {

  @Test
  fun `parses single query`() {
    val content = """
      -- name: getAll :many
      SELECT * FROM users;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].name).isEqualTo("getAll")
    assertThat(result[0].command).isEqualTo(":many")
    assertThat(result[0].sql).isEqualTo("SELECT * FROM users")
    assertThat(result[0].comments).isEmpty()
  }

  @Test
  fun `parses multiple queries`() {
    val content = """
      -- name: getAll :many
      SELECT * FROM users;

      -- name: getById :one
      SELECT * FROM users WHERE id = $1;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(2)
    assertThat(result[0].name).isEqualTo("getAll")
    assertThat(result[0].command).isEqualTo(":many")
    assertThat(result[1].name).isEqualTo("getById")
    assertThat(result[1].command).isEqualTo(":one")
    assertThat(result[1].sql).isEqualTo($$"SELECT * FROM users WHERE id = $1")
  }

  @Test
  fun `preserves comments before annotation`() {
    val content = """
      -- Execrows without parameters.
      -- name: deleteAll :execrows
      DELETE FROM type;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].comments).containsExactly("Execrows without parameters.")
  }

  @Test
  fun `preserves multiple comment lines`() {
    val content = """
      -- Query using pgcrypto for password hashing
      -- name: createUser :exec
      INSERT INTO user_credentials (username, password_hash)
      VALUES ($1, crypt($2, gen_salt('bf')));
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].comments).containsExactly("Query using pgcrypto for password hashing")
    assertThat(result[0].sql).isEqualTo(
      $$"INSERT INTO user_credentials (username, password_hash)\nVALUES ($1, crypt($2, gen_salt('bf')))",
    )
  }

  @Test
  fun `handles all command types`() {
    val content = """
      -- name: q1 :one
      SELECT 1;

      -- name: q2 :many
      SELECT 1;

      -- name: q3 :exec
      DELETE FROM t;

      -- name: q4 :execrows
      DELETE FROM t;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(4)
    assertThat(result[0].command).isEqualTo(":one")
    assertThat(result[1].command).isEqualTo(":many")
    assertThat(result[2].command).isEqualTo(":exec")
    assertThat(result[3].command).isEqualTo(":execrows")
  }

  @Test
  fun `handles multi-line SQL`() {
    val content = """
      -- name: createUser :exec
      INSERT INTO users (email, age, zip_code)
      VALUES ($1, $2, $3);
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].sql).isEqualTo($$"INSERT INTO users (email, age, zip_code)\nVALUES ($1, $2, $3)")
  }

  @Test
  fun `handles complex SQL with subqueries`() {
    val content = """
      -- name: verifyPassword :one
      SELECT EXISTS(
        SELECT 1 FROM user_credentials
        WHERE username = $1
        AND password_hash = crypt($2, password_hash)
      ) AS valid;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].name).isEqualTo("verifyPassword")
    assertThat(result[0].sql).isEqualTo(
      """
      SELECT EXISTS(
        SELECT 1 FROM user_credentials
        WHERE username = $1
        AND password_hash = crypt($2, password_hash)
      ) AS valid
      """.trimIndent(),
    )
  }

  @Test
  fun `handles empty file`() {
    val result = QueryFileParser.parse("")
    assertThat(result).isEmpty()
  }

  @Test
  fun `handles file with only comments`() {
    val content = """
      -- This is just a comment
      -- Another comment
    """.trimIndent()

    val result = QueryFileParser.parse(content)
    assertThat(result).isEmpty()
  }

  @Test
  fun `comments only attach to immediately following query`() {
    val content = """
      -- Comment for first query.
      -- name: first :one
      SELECT 1;

      -- Comment for second query.
      -- name: second :many
      SELECT 1;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(2)
    assertThat(result[0].comments).containsExactly("Comment for first query.")
    assertThat(result[1].comments).containsExactly("Comment for second query.")
  }

  @Test
  fun `parses the all_types scenario format`() {
    val content = """
      -- name: all :many
      SELECT * FROM type;

      -- name: single :one
      SELECT string_type FROM type;

      -- name: insertOne :execrows
      INSERT INTO type(string_type) VALUES ($1);

      -- Execrows without parameters.
      -- name: deleteAll :execrows
      DELETE FROM type;

      -- Exec without parameters.
      -- name: resetTypes :exec
      CALL reset_type_table();

      -- Exec with parameters.
      -- name: updateStringType :exec
      CALL update_string_type($1, $2);
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(6)
    assertThat(result[0].name).isEqualTo("all")
    assertThat(result[1].name).isEqualTo("single")
    assertThat(result[2].name).isEqualTo("insertOne")
    assertThat(result[3].name).isEqualTo("deleteAll")
    assertThat(result[3].comments).containsExactly("Execrows without parameters.")
    assertThat(result[4].name).isEqualTo("resetTypes")
    assertThat(result[5].name).isEqualTo("updateStringType")
    assertThat(result[5].sql).isEqualTo($$"CALL update_string_type($1, $2)")
  }

  @Test
  fun `strips trailing semicolons`() {
    val content = """
      -- name: q1 :one
      SELECT 1;
    """.trimIndent()

    val result = QueryFileParser.parse(content)
    assertThat(result[0].sql).isEqualTo("SELECT 1")
  }

  @Test
  fun `handles CALL statements`() {
    val content = """
      -- name: resetTypes :exec
      CALL reset_type_table();
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].sql).isEqualTo("CALL reset_type_table()")
  }
}
