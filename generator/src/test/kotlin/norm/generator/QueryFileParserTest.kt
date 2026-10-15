package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import org.junit.jupiter.api.Nested
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
    assertThat(result[0].command).isEqualTo(Command.MANY)
    assertThat(result[0].sql).isEqualTo("SELECT * FROM users")
    assertThat(result[0].comments).isEmpty()
  }

  @Test
  fun `parses multiple queries`() {
    val content = """
      -- name: getAll :many
      SELECT * FROM users;

      -- name: getById :one
      SELECT * FROM users WHERE id = ?;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(2)
    assertThat(result[0].name).isEqualTo("getAll")
    assertThat(result[0].command).isEqualTo(Command.MANY)
    assertThat(result[1].name).isEqualTo("getById")
    assertThat(result[1].command).isEqualTo(Command.ONE)
    assertThat(result[1].sql).isEqualTo("SELECT * FROM users WHERE id = ?")
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
      VALUES (?, crypt(?, gen_salt('bf')));
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].comments).containsExactly("Query using pgcrypto for password hashing")
    assertThat(result[0].sql).isEqualTo(
      "INSERT INTO user_credentials (username, password_hash)\nVALUES (?, crypt(?, gen_salt('bf')))",
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
    assertThat(result[0].command).isEqualTo(Command.ONE)
    assertThat(result[1].command).isEqualTo(Command.MANY)
    assertThat(result[2].command).isEqualTo(Command.EXEC)
    assertThat(result[3].command).isEqualTo(Command.EXEC_ROWS)
  }

  @Test
  fun `handles multi-line SQL`() {
    val content = """
      -- name: createUser :exec
      INSERT INTO users (email, age, zip_code)
      VALUES (?, ?, ?);
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].sql).isEqualTo("INSERT INTO users (email, age, zip_code)\nVALUES (?, ?, ?)")
  }

  @Test
  fun `handles complex SQL with subqueries`() {
    val content = """
      -- name: verifyPassword :one
      SELECT EXISTS(
        SELECT 1 FROM user_credentials
        WHERE username = ?
        AND password_hash = crypt(?, password_hash)
      ) AS valid;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].name).isEqualTo("verifyPassword")
    assertThat(result[0].sql).isEqualTo(
      """
      SELECT EXISTS(
        SELECT 1 FROM user_credentials
        WHERE username = ?
        AND password_hash = crypt(?, password_hash)
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
      INSERT INTO type(string_type) VALUES (?);

      -- Execrows without parameters.
      -- name: deleteAll :execrows
      DELETE FROM type;

      -- Exec without parameters.
      -- name: resetTypes :exec
      CALL reset_type_table();

      -- Exec with parameters.
      -- name: updateStringType :exec
      CALL update_string_type(?, ?);
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
    assertThat(result[5].sql).isEqualTo("CALL update_string_type(?, ?)")
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

  @Test
  fun `converts named parameters to positional`() {
    val content = """
      -- name: updateUser :exec
      UPDATE users SET name = :name, age = :age WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result).hasSize(1)
    assertThat(result[0].sql).isEqualTo("UPDATE users SET name = ?, age = ? WHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "name", 2 to "age", 3 to "id"))
  }

  @Test
  fun `same named parameter used twice creates separate placeholders`() {
    val content = """
      -- name: search :many
      SELECT * FROM users WHERE name = :name OR email LIKE :name;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE name = ? OR email LIKE ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "name", 2 to "name"))
  }

  @Test
  fun `does not treat double colon cast as named parameter`() {
    val content = """
      -- name: getCount :one
      SELECT COUNT(id)::int AS total FROM users WHERE name = :name;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT COUNT(id)::int AS total FROM users WHERE name = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "name"))
  }

  @Test
  fun `handles named parameter that is a SQL keyword`() {
    val content = """
      -- name: listUsers :many
      SELECT * FROM users WHERE age > :limit;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE age > ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "limit"))
  }

  @Test
  fun `does not allow mixing named and positional parameters`() {
    val content = """
      -- name: mixed :one
      SELECT * FROM users WHERE name = :name AND age > ?;
    """.trimIndent()

    assertFailure { QueryFileParser.parse(content) }
      .messageContains("mix")
  }

  @Test
  fun `named parameters in INSERT VALUES`() {
    val content = """
      -- name: createUser :exec
      INSERT INTO users (email, age, zip_code)
      VALUES (:email, :age, :zipCode);
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo(
      "INSERT INTO users (email, age, zip_code)\nVALUES (?, ?, ?)",
    )
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "email", 2 to "age", 3 to "zipCode"))
  }

  @Test
  fun `named parameters with coalesce update pattern`() {
    val content = """
      -- name: patchOrganization :exec
      UPDATE organization
      SET
        name = coalesce(:name, organization.name),
        slug = coalesce(:slug, organization.slug),
        tier = coalesce(:tier, organization.tier)
      WHERE salesforce_account_id = :salesforceAccountId;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo(
      """
      UPDATE organization
      SET
        name = coalesce(?, organization.name),
        slug = coalesce(?, organization.slug),
        tier = coalesce(?, organization.tier)
      WHERE salesforce_account_id = ?
      """.trimIndent(),
    )
    assertThat(result[0].namedParameters).isEqualTo(
      mapOf(1 to "name", 2 to "slug", 3 to "tier", 4 to "salesforceAccountId"),
    )
  }

  @Test
  fun `named parameter after double colon cast`() {
    val content = """
      -- name: insertTyped :exec
      INSERT INTO t (val) VALUES (:value::integer);
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("INSERT INTO t (val) VALUES (?::integer)")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "value"))
  }

  @Test
  fun `queries without named parameters have empty namedParameters`() {
    val content = """
      -- name: getById :one
      SELECT * FROM users WHERE id = ?;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE id = ?")
    assertThat(result[0].namedParameters).isEmpty()
  }

  @Test
  fun `named parameter inside string literal is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users WHERE name LIKE ':notaparam' AND id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE name LIKE ':notaparam' AND id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter inside a block comment is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users /* :notaparam */ WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users /* :notaparam */ WHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter inside a quoted identifier is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT "a:b" FROM users WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("""SELECT "a:b" FROM users WHERE id = ?""")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter inside a dollar-quoted string is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT ${'$'}${'$'}:notaparam${'$'}${'$'} FROM users WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT \$\$:notaparam\$\$ FROM users WHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `question mark inside a string literal does not trip the mixed-style guard`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users WHERE note = 'really?' AND id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE note = 'really?' AND id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `a doubled question mark is pgjdbc's escape and does not trip the mixed-style guard`() {
    val content = """
      -- name: findByKey :many
      SELECT * FROM document WHERE data ?? 'key' AND id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM document WHERE data ?? 'key' AND id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `a doubled question mark next to a positional placeholder still counts that placeholder as positional`() {
    val content = """
      -- name: findByKey :many
      SELECT * FROM document WHERE data ?? 'key' AND id = ? AND title = :title;
    """.trimIndent()

    assertFailure { QueryFileParser.parse(content) }
      .messageContains("mix")
  }

  @Test
  fun `genuinely mixed named and positional parameters still throw`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users WHERE note = ? AND id = :id;
    """.trimIndent()

    assertFailure { QueryFileParser.parse(content) }
      .messageContains("mix")
  }

  @Test
  fun `named parameter after the inner close of a nested block comment is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users /* a /* b */ :notaparam */ WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users /* a /* b */ :notaparam */ WHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named-parameter-shaped text after a backslash-escaped quote in an E-string is not converted`() {
    // The old findClosingQuote ended the literal at the backslash-escaped quote (it had no E-string
    // awareness), so it would have read '\'' as the terminator and converted the ":x" that follows
    // as a real named parameter. skipLexicalToken understands E'...' backslash escapes, so the
    // whole literal -- ":x" included -- is skipped as one token. Accepted behavior change, matching
    // PostgreSQL's own E'' semantics.
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users WHERE note = E'it\'s :x' AND id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("""SELECT * FROM users WHERE note = E'it\'s :x' AND id = ?""")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Nested
  inner class UnrecognizedCommand {

    @Test
    fun `unrecognized command annotation names the query, the annotation, and the line`() {
      val content = """
        -- name: getAll :many
        SELECT * FROM users;

        -- name: foo :bogus
        SELECT 1;
      """.trimIndent()

      val exception = assertFailure { QueryFileParser.parse(content) }
      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("foo")
      exception.messageContains(":bogus")
      exception.messageContains("line 4")
    }

    @Test
    fun `command matching is case-sensitive`() {
      val content = """
        -- name: getAll :ONE
        SELECT 1;
      """.trimIndent()

      val exception = assertFailure { QueryFileParser.parse(content) }
      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains(":ONE")
    }
  }

  @Nested
  inner class BodyTermination {

    private fun parseWithFollowingQuery(firstBody: String, lineEnding: String = "\n"): List<ParsedQuery> {
      val content = "-- name: first :one\n$firstBody\n\n-- Doc for second.\n-- name: second :many\nSELECT 2;\n"
      val result = QueryFileParser.parse(content.replace("\n", lineEnding))
      val secondAnnotationLine = content.lines().indexOfFirst { it.startsWith("-- name: second") } + 1
      assertThat(result).hasSize(2)
      assertThat(result[1].name).isEqualTo("second")
      assertThat(result[1].comments).containsExactly("Doc for second.")
      assertThat(result[1].sourceLine).isEqualTo(secondAnnotationLine)
      assertThat(result[1].sql).isEqualTo("SELECT 2")
      return result
    }

    @Test
    fun `semicolon at a line end inside a dollar-quoted DO block does not end the body`() {
      val result = parseWithFollowingQuery("DO $$ BEGIN PERFORM 1;\nEND $$;")

      assertThat(result[0].sql).isEqualTo("DO $$ BEGIN PERFORM 1;\nEND $$")
    }

    @Test
    fun `semicolon at a line end inside a string literal does not end the body`() {
      val result = parseWithFollowingQuery("SELECT 'a;\nb' AS v;")

      assertThat(result[0].sql).isEqualTo("SELECT 'a;\nb' AS v")
    }

    @Test
    fun `semicolon at a line end inside a block comment does not end the body`() {
      val result = parseWithFollowingQuery("SELECT 1 /* note;\nmore */ AS v;")

      assertThat(result[0].sql).isEqualTo("SELECT 1 /* note;\nmore */ AS v")
    }

    @Test
    fun `semicolon after an escaped quote inside an escape string does not end the body`() {
      val result = parseWithFollowingQuery("SELECT E'\\';\n' AS v;")

      assertThat(result[0].sql).isEqualTo("SELECT E'\\';\n' AS v")
    }

    @Test
    fun `semicolon at a line end inside a quoted identifier does not end the body`() {
      val result = parseWithFollowingQuery("SELECT 1 AS \"a;\nb\";")

      assertThat(result[0].sql).isEqualTo("SELECT 1 AS \"a;\nb\"")
    }

    @Test
    fun `semicolon at a line end inside a nested block comment does not end the body`() {
      val result = parseWithFollowingQuery("SELECT 1 /* outer /* inner; */ still;\n*/ AS v;")

      assertThat(result[0].sql).isEqualTo("SELECT 1 /* outer /* inner; */ still;\n*/ AS v")
    }

    @Test
    fun `line comment ending in a semicolon does not end the body`() {
      val result = parseWithFollowingQuery("SELECT 1\n-- trailing;\n;")

      assertThat(result[0].sql).isEqualTo("SELECT 1\n-- trailing;")
    }

    @Test
    fun `semicolon at a line end inside a tagged dollar quote does not end the body`() {
      val result = parseWithFollowingQuery("SELECT ${'$'}tag${'$'}a;\nb${'$'}tag${'$'} AS v;")

      assertThat(result[0].sql).isEqualTo("SELECT ${'$'}tag${'$'}a;\nb${'$'}tag${'$'} AS v")
    }

    @Test
    fun `line comment after the terminator is not part of the sql or the next query's comments`() {
      val result = parseWithFollowingQuery("SELECT 1; -- note")

      assertThat(result[0].sql).isEqualTo("SELECT 1")
    }

    @Test
    fun `block comment after the terminator is ignored`() {
      val result = parseWithFollowingQuery("SELECT 1; /* note */")

      assertThat(result[0].sql).isEqualTo("SELECT 1")
    }

    @Test
    fun `crlf line endings yield newline-joined sql with correct comments and source lines`() {
      val result = parseWithFollowingQuery("SELECT 'a;\nb' AS v;", lineEnding = "\r\n")

      assertThat(result[0].sql).isEqualTo("SELECT 'a;\nb' AS v")
    }

    @Test
    fun `second statement on the terminator line is part of the body`() {
      val result = QueryFileParser.parse("-- name: first :one\nSELECT 1; SELECT 2;")

      assertThat(result).hasSize(1)
      assertThat(result[0].sql).isEqualTo("SELECT 1; SELECT 2")
    }

    @Test
    fun `second statement on a later line throws`() {
      val content = "-- name: first :one\nSELECT 1;\nSELECT 2;\n-- name: b :one\nSELECT 3;"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("first")
      exception.messageContains("line 1")
    }

    @Test
    fun `annotation inside an open dollar quote is body text`() {
      val content = "-- name: first :exec\nDO $$\n-- name: b :one\nSELECT 1;\n$$;"

      val result = QueryFileParser.parse(content)

      assertThat(result).hasSize(1)
      assertThat(result[0].sql).isEqualTo("DO $$\n-- name: b :one\nSELECT 1;\n$$")
    }

    @Test
    fun `annotation inside a block comment between queries starts a query`() {
      val content = "-- name: first :one\nSELECT 1;\n/*\n-- name: b :one\nSELECT 1;\n*/\n-- name: c :one\nSELECT 3;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("first", "b", "c")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 1", "SELECT 3")
      assertThat(result[1].sourceLine).isEqualTo(4)
      assertThat(result[2].sourceLine).isEqualTo(7)
    }

    @Test
    fun `annotation after an unterminated body starts the next query`() {
      val content = "-- name: first :one\nSELECT 1\n\n-- name: second :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("first", "second")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
      assertThat(result[1].sql).isEqualTo("SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }
  }

  @Nested
  inner class OutsideBody {

    @Test
    fun `non-breaking space after the terminator is whitespace`() {
      val content = "-- name: a :one\nSELECT 1;\u00A0\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].sourceLine).isEqualTo(3)
    }

    @Test
    fun `annotation and doc comment indented with a non-breaking space are recognized`() {
      val content = "-- name: a :one\nSELECT 1;\n\u00A0-- Doc.\n\u00A0-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `block comment left open between queries does not hide later annotations`() {
      val content = "-- name: a :one\nSELECT 1;\n/* oops\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `block comment left open before the first annotation does not hide it`() {
      val result = QueryFileParser.parse("\n/* oops\n-- name: a :one\nSELECT 1;")

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
      assertThat(result[0].sourceLine).isEqualTo(3)
    }

    @Test
    fun `nested block comment line left open at end of file is ignored`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT 1;\n/* /* */")

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
    }

    @Test
    fun `block comment closed at end of file is accepted`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT 1;\n/* /* */ */")

      assertThat(result.map { it.name }).containsExactly("a")
    }

    @Test
    fun `unterminated string literal after the terminator throws`() {
      val exception =
        assertFailure { QueryFileParser.parse("-- name: a :one\nSELECT 1;\n'oops\n-- name: b :one\nSELECT 2;") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 3")
    }

    @Test
    fun `unterminated dollar quote before the first annotation does not hide it`() {
      val result = QueryFileParser.parse("${'$'}${'$'} oops\n-- name: a :one\nSELECT 1;")

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
      assertThat(result[0].sourceLine).isEqualTo(2)
    }

    @Test
    fun `unterminated string inside a body does not hide later annotations`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT 'oops;\n-- name: b :one")

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 'oops")
      assertThat(result[1].sourceLine).isEqualTo(3)
    }
  }

  @Nested
  inner class LineRuleFallbacks {

    private fun parseWithFollowingQuery(firstBody: String, secondSql: String = "SELECT 2"): List<ParsedQuery> {
      val content = "-- name: first :one\n$firstBody\n\n-- Doc for second.\n-- name: second :many\n$secondSql;\n"
      val result = QueryFileParser.parse(content)
      assertThat(result.map { it.name }).containsExactly("first", "second")
      assertThat(result[1].comments).containsExactly("Doc for second.")
      assertThat(result[1].sql).isEqualTo(secondSql)
      assertThat(result[1].sourceLine).isEqualTo(content.lines().indexOfFirst { it.startsWith("-- name: second") } + 1)
      return result
    }

    @Test
    fun `comment ending in a semicolon cuts a body that has no terminator`() {
      val content = "-- name: findT :one\nSELECT id FROM t\nWHERE id = ?\n-- AND id > 0;\n\n" +
        "-- Counts the rows in log.\n-- name: countLog :one\nSELECT count(*) FROM log;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("findT", "countLog")
      assertThat(result[0].sql).isEqualTo("SELECT id FROM t\nWHERE id = ?\n-- AND id > 0")
      assertThat(result[1].comments).containsExactly("Counts the rows in log.")
      assertThat(result[1].sql).isEqualTo("SELECT count(*) FROM log")
      assertThat(result[1].sourceLine).isEqualTo(7)
    }

    @Test
    fun `comment ending in a semicolon cuts a body directly before the next doc comment`() {
      val content = "-- name: findT :one\nSELECT id FROM t\n-- AND id > 0;\n" +
        "-- Counts the rows in log.\n-- name: countLog :one\nSELECT count(*) FROM log;"

      val result = QueryFileParser.parse(content)

      assertThat(result[0].sql).isEqualTo("SELECT id FROM t\n-- AND id > 0")
      assertThat(result[1].comments).containsExactly("Counts the rows in log.")
      assertThat(result[1].sourceLine).isEqualTo(5)
    }

    @Test
    fun `doc comment line after an unterminated body stays in that body`() {
      val content = "-- name: a :one\nSELECT 1\n-- doc\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 1\n-- doc")
      assertThat(result[1].comments).isEmpty()
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `one-line CREATE RULE with a semicolon inside parentheses is one statement`() {
      val body = "CREATE RULE r AS ON INSERT TO t DO ALSO (INSERT INTO log VALUES (1); INSERT INTO log VALUES (2))"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `multi-line CREATE RULE with a semicolon at a line end inside parentheses is one statement`() {
      val body = "CREATE RULE r AS ON INSERT TO t DO ALSO (\n  INSERT INTO log VALUES (1);\n" +
        "  INSERT INTO log VALUES (2)\n)"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `doubled semicolon leaves one semicolon in the sql`() {
      val result = parseWithFollowingQuery("SELECT 1;;")

      assertThat(result[0].sql).isEqualTo("SELECT 1;")
    }

    @Test
    fun `line holding only a semicolon between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `line holding a semicolon and a comment between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n; -- nothing\n\n-- Doc b.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).containsExactly("Doc b.")
      assertThat(result[1].sourceLine).isEqualTo(6)
    }

    @Test
    fun `line holding only semicolons between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n;;\n ; ;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).isEmpty()
    }

    @Test
    fun `semicolon-only line clears doc comments before it`() {
      val content = "-- name: a :one\nSELECT 1;\n-- doc\n;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].comments).isEmpty()
    }

    @Test
    fun `block comments and prose before the first annotation are accepted`() {
      val content = "/* Generated from queries/*.sql */\nDon't edit by hand\n-- Doc.\n-- name: a :one\nSELECT 1;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].comments).containsExactly("Doc.")
      assertThat(result[0].sourceLine).isEqualTo(4)
    }

    @Test
    fun `prose before the first annotation clears earlier doc comments`() {
      val result = QueryFileParser.parse("-- stale\nDon't edit by hand\n-- name: a :one\nSELECT 1;")

      assertThat(result[0].comments).isEmpty()
    }

    @Test
    fun `one-line block comment containing a glob between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n/* see docs/*.md */\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `multi-line block comment between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n/*\nfoo\n*/\n-- Doc.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(7)
    }

    @Test
    fun `unclosed string cut at a comment line ending in a semicolon keeps later doc comments`() {
      val content = "-- name: a :one\nSELECT 'oops\n-- x;\n-- doc\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 'oops\n-- x")
      assertThat(result[1].comments).containsExactly("doc")
      assertThat(result[1].sql).isEqualTo("SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(5)
    }

    @Test
    fun `nested block comment between queries hides the SQL inside it`() {
      val content = "-- name: a :one\nSELECT 1;\n\n/*\nSELECT x /* old note */\nFROM y;\n*/\n" +
        "-- name: b :one\nSELECT 2;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(8)
    }

    @Test
    fun `annotation inside a block comment between queries does not make later lines throw`() {
      val content = "-- name: a :one\nSELECT 1;\n/*\n-- name: old :one\nSELECT 2;\nRemoved because it was slow.\n*/\n" +
        "-- name: b :one\nSELECT 3;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "old", "b")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2", "SELECT 3")
      assertThat(result.map { it.sourceLine }).containsExactly(1, 4, 8)
    }

    @Test
    fun `block comment line with an unclosed nested open keeps later stray lines from throwing`() {
      val content = "-- name: a :one\nSELECT 1;\n/* see docs/*.md */\nSELECT 9;\n-- name: b :one\nSELECT 2;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(5)
    }

    @Test
    fun `stray text after a comment cut throws naming the query`() {
      val content = "-- name: a :one\nSELECT 1\n-- c;\nORDER BY 1\n-- name: b :one\nSELECT 2;"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 4")
    }
  }

  @Nested
  inner class FunctionBodies {

    private fun parseWithFollowingQuery(firstBody: String): List<ParsedQuery> {
      val content = "-- name: first :exec\n$firstBody\n\n-- Doc for second.\n-- name: second :one\nSELECT 2;\n"
      val result = QueryFileParser.parse(content)
      assertThat(result.map { it.name }).containsExactly("first", "second")
      assertThat(result[1].comments).containsExactly("Doc for second.")
      assertThat(result[1].sql).isEqualTo("SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(content.lines().indexOfFirst { it.startsWith("-- name: second") } + 1)
      return result
    }

    @Test
    fun `semicolon inside a one-line BEGIN ATOMIC body does not end the query`() {
      val body = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; END"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `semicolons inside a multi-line lower-case BEGIN ATOMIC body do not end the query`() {
      val body = "create function f() returns int language sql\nbegin atomic\n  select 1;\n  select 2;\nend"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `CREATE OR REPLACE PROCEDURE with a BEGIN ATOMIC body is one query`() {
      val body = "CREATE OR REPLACE PROCEDURE p() LANGUAGE sql\nBEGIN ATOMIC\n  DELETE FROM t;\n  DELETE FROM u;\nEND"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `CASE inside a BEGIN ATOMIC body does not close the body early`() {
      val body = "CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n" +
        "  SELECT CASE WHEN true THEN 1 ELSE 2 END;\n  SELECT 3;\nEND"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `CASE and END in a plain SELECT end at the semicolon`() {
      val result = parseWithFollowingQuery("SELECT CASE WHEN true THEN 1 END;")

      assertThat(result[0].sql).isEqualTo("SELECT CASE WHEN true THEN 1 END")
    }

    @Test
    fun `a column named begin in a SELECT ends at the semicolon`() {
      val result = parseWithFollowingQuery("SELECT begin, \"end\" FROM t;")

      assertThat(result[0].sql).isEqualTo("SELECT begin, \"end\" FROM t")
    }

    @Test
    fun `a column named begin in CREATE TABLE ends at the semicolon`() {
      val result = parseWithFollowingQuery("CREATE TABLE t (begin int, \"end\" int);")

      assertThat(result[0].sql).isEqualTo("CREATE TABLE t (begin int, \"end\" int)")
    }

    @Test
    fun `a parameter named begin in a function signature does not open a body`() {
      val body = "CREATE FUNCTION g(begin int) RETURNS int AS 'select 1' LANGUAGE sql"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `BEGIN and END inside a dollar-quoted function body end at the final semicolon`() {
      val body = "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `function named begin does not open a body`() {
      val body = "CREATE FUNCTION begin() RETURNS int LANGUAGE sql AS 'SELECT 1'"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `return type named begin does not open a body`() {
      val body = "CREATE FUNCTION f() RETURNS SETOF begin LANGUAGE sql AS $$ SELECT * FROM begin $$"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `column named begin inside a BEGIN ATOMIC body does not open a second level`() {
      val body = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT begin FROM t; END"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `comment between BEGIN and ATOMIC does not hide the body`() {
      val body = "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN /* note */ -- more\n" +
        " ATOMIC SELECT 1; SELECT 2; END"

      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @Test
    fun `annotation after a BEGIN ATOMIC body that never closes starts the next query`() {
      val content = "-- name: first :exec\nCREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1;\n\n" +
        "-- name: second :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("first", "second")
      assertThat(result[1].sql).isEqualTo("SELECT 2")
    }
  }
}
