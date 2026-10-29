package norm.generator

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.endsWith
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import norm.generator.baseline.BLOCK_COMMENT_ISSUE_INPUT
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users WHERE note = E'it\'s :x' AND id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("""SELECT * FROM users WHERE note = E'it\'s :x' AND id = ?""")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter inside a line comment is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT * FROM users -- :notaparam
      WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users -- :notaparam\nWHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter inside an E-string is not converted`() {
    val content = """
      -- name: findByPattern :many
      SELECT E':notaparam' FROM users WHERE id = :id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT E':notaparam' FROM users WHERE id = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `named parameter glued to an operator is converted`() {
    val content = """
      -- name: findById :one
      SELECT * FROM users WHERE id=:id;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE id=?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "id"))
  }

  @Test
  fun `colon equals is not a named parameter but the parameter after it is`() {
    val content = """
      -- name: assign :one
      SELECT a := :value;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT a := ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "value"))
  }

  @Test
  fun `a reserved word is a named parameter name`() {
    val content = """
      -- name: listUsers :many
      SELECT * FROM users WHERE age > :from AND x < :select;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE age > ? AND x < ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "from", 2 to "select"))
  }

  @Test
  fun `a named parameter name may hold non-ASCII letters and a dollar sign`() {
    val content = """
      -- name: listUsers :many
      SELECT * FROM users WHERE a = :größe AND b = :ab${'$'}c;
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE a = ? AND b = ?")
    assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "größe", 2 to "ab${'$'}c"))
  }

  @Test
  fun `a colon before a quoted identifier is not a named parameter`() {
    val content = """
      -- name: listUsers :many
      SELECT * FROM users WHERE a = :"q";
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("""SELECT * FROM users WHERE a = :"q"""")
    assertThat(result[0].namedParameters).isEmpty()
  }

  @Test
  fun `a colon before a string literal is not a named parameter`() {
    val content = """
      -- name: listUsers :many
      SELECT * FROM users WHERE a = :e'x';
    """.trimIndent()

    val result = QueryFileParser.parse(content)

    assertThat(result[0].sql).isEqualTo("SELECT * FROM users WHERE a = :e'x'")
    assertThat(result[0].namedParameters).isEmpty()
  }

  @Test
  fun `a NUL character names the query and the line`() {
    val content = "-- name: getAll :many\nSELECT 1;\n\n-- name: broken :one\nSELECT 'a\u0000b' WHERE id = :id;"

    val exception = assertFailure { QueryFileParser.parse(content) }

    exception.isInstanceOf(IllegalArgumentException::class)
    exception.messageContains("broken")
    exception.messageContains("line 4")
    exception.messageContains("NUL")
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
    fun `block comment after the terminator is not part of the sql`() {
      val result = parseWithFollowingQuery("SELECT 1; /* note */")

      assertThat(result[0].sql).isEqualTo("SELECT 1")
    }

    @Test
    fun `second statement on the terminator line stays in the body`() {
      val result = QueryFileParser.parse("-- name: first :one\nSELECT 1; SELECT 2;")

      assertThat(result).hasSize(1)
      assertThat(result[0].sql).isEqualTo("SELECT 1; SELECT 2")
    }

    @Test
    fun `second statement on a later line throws naming the query and its annotation line`() {
      val content = "-- name: first :one\nSELECT 1;\nSELECT 2;\n-- name: b :one\nSELECT 3;"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("first")
      exception.messageContains("line 1")
      exception.messageContains("line 3")
    }

    @Test
    fun `annotation inside an open dollar quote is body text`() {
      val content = "-- name: first :exec\nDO $$\n-- name: b :one\nSELECT 1;\n$$;"

      val result = QueryFileParser.parse(content)

      assertThat(result).hasSize(1)
      assertThat(result[0].sql).isEqualTo("DO $$\n-- name: b :one\nSELECT 1;\n$$")
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

    @Test
    fun `crlf line endings yield newline-joined sql with correct comments and source lines`() {
      val result = parseWithFollowingQuery("SELECT 'a;\nb' AS v;", lineEnding = "\r\n")

      assertThat(result[0].sql).isEqualTo("SELECT 'a;\nb' AS v")
    }

    @Test
    fun `BEGIN ATOMIC body with semicolons at line ends is one query ending at END`() {
      val result =
        parseWithFollowingQuery(
          "CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT 1;\n  SELECT 2;\nEND;",
        )

      assertThat(result[0].sql)
        .isEqualTo("CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT 1;\n  SELECT 2;\nEND")
    }

    @Test
    fun `a doubled semicolon leaves one semicolon in the sql`() {
      val result = parseWithFollowingQuery("SELECT 1;;")

      assertThat(result[0].sql).isEqualTo("SELECT 1;")
    }

    @Test
    fun `a CREATE RULE with a semicolon inside parentheses is one statement`() {
      val oneLine = "CREATE RULE r AS ON INSERT TO t DO ALSO (INSERT INTO log VALUES (1); INSERT INTO log VALUES (2))"
      val multiLine = "CREATE RULE r AS ON INSERT TO t DO ALSO (\n  INSERT INTO log VALUES (1);\n" +
        "  INSERT INTO log VALUES (2))"

      assertThat(parseWithFollowingQuery("$oneLine;")[0].sql).isEqualTo(oneLine)
      assertThat(parseWithFollowingQuery("$multiLine;")[0].sql).isEqualTo(multiLine)
    }

    @Test
    fun `the sql keeps comments inside the statement`() {
      val content = "-- name: a :one\n-- inner\nSELECT 1;"

      assertThat(QueryFileParser.parse(content)[0].sql).isEqualTo("-- inner\nSELECT 1")
    }

    @Test
    fun `a line comment that starts after SQL on its line is not an annotation`() {
      val content = "-- name: a :one\nSELECT 1 -- name: b :one\n;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].sql).isEqualTo("SELECT 1 -- name: b :one")
    }

    @Test
    fun `an annotation may omit the space after the dashes`() {
      val result = QueryFileParser.parse("--name:x :one\nSELECT 1;")

      assertThat(result.map { it.name }).containsExactly("x")
    }

    @Test
    fun `an indented annotation starts a query on its own line`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT 1;\n  -- name: b :one\nSELECT 2;")

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].sourceLine).isEqualTo(3)
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = ["00A0", "2003"])
    fun `a body line indented with a Unicode space ends at its terminator`(codePoint: String) {
      val space = codePoint.toInt(radix = 16).toChar()
      val content = "-- name: a :one\n${space}SELECT 1;\n\n-- Doc.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(5)
    }

    @Test
    fun `a non-breaking space inside a body stays in the sql`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT\u00A01;\n")

      assertThat(result.single().sql).isEqualTo("SELECT\u00A01")
    }

    @Test
    fun `a non-breaking space that hides a dollar quote from the lexer does not break the split`() {
      val content = "-- name: a :one\nSELECT 1 AS a\u00A0$$;\n\n-- Doc.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 1 AS a\u00A0$$")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(5)
    }

    @Test
    fun `a non-breaking space between dollar signs does not break the split`() {
      val space = '\u00A0'
      val body = "SELECT ${'$'}$space${'$'}a;\nb${'$'}$space${'$'} AS v"

      val result = QueryFileParser.parse("-- name: a :one\n$body;")

      assertThat(result.single().sql).isEqualTo(body)
    }

    @Test
    fun `a BEGIN ATOMIC body with named parameters stays one query`() {
      val content =
        "-- name: first :exec\nCREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT :one;\n" +
          "  SELECT :two;\nEND;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result).hasSize(1)
      assertThat(result[0].sql)
        .isEqualTo("CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT ?;\n  SELECT ?;\nEND")
      assertThat(result[0].namedParameters).isEqualTo(mapOf(1 to "one", 2 to "two"))
    }

    @Test
    fun `a BEGIN ATOMIC body with positional placeholders stays one query`() {
      val content =
        "-- name: first :exec\nCREATE PROCEDURE p(a int) LANGUAGE sql\nBEGIN ATOMIC\n  SELECT 1 WHERE 1 = ?;\n" +
          "  SELECT 2;\nEND;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result).hasSize(1)
      assertThat(result[0].sql).endsWith("SELECT 2;\nEND")
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; END",
        "create function f() returns int language sql\nbegin atomic\n  select 1;\n  select 2;\nend",
        "CREATE OR REPLACE PROCEDURE p() LANGUAGE sql\nBEGIN ATOMIC\n  DELETE FROM t;\n  DELETE FROM u;\nEND",
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql\nBEGIN ATOMIC\n  SELECT CASE WHEN true THEN 1 ELSE 2 END;\n" +
          "  SELECT 3;\nEND",
        "CREATE FUNCTION g(begin int) RETURNS int AS 'select 1' LANGUAGE sql",
        "CREATE FUNCTION f() RETURNS int AS \$\$ BEGIN RETURN 1; END; \$\$ LANGUAGE plpgsql",
        "CREATE FUNCTION begin() RETURNS int LANGUAGE sql AS 'SELECT 1'",
        "CREATE FUNCTION f() RETURNS SETOF begin LANGUAGE sql AS \$\$ SELECT * FROM begin \$\$",
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT begin FROM t; END",
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN /* note */ -- more\n ATOMIC SELECT 1; SELECT 2; END",
      ],
    )
    fun `a function or procedure definition is one query that ends at its final semicolon`(body: String) {
      val result = parseWithFollowingQuery("$body;")

      assertThat(result[0].sql).isEqualTo(body)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
      strings = [
        "SELECT CASE WHEN true THEN 1 END",
        "SELECT begin, \"end\" FROM t",
        "CREATE TABLE t (begin int, \"end\" int)",
      ],
    )
    fun `a statement that merely uses BEGIN or END words ends at its semicolon`(body: String) {
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

  @Nested
  inner class BetweenQueries {

    @Test
    fun `an annotation inside a block comment between queries is part of the comment`() {
      val result = QueryFileParser.parse(BLOCK_COMMENT_ISSUE_INPUT)

      assertThat(result.map { it.name }).containsExactly("first", "c")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 3")
      assertThat(result[1].sourceLine).isEqualTo(9)
      assertThat(result[1].comments).isEmpty()
    }

    @Test
    fun `a doc comment inside a block comment between queries is not a comment of the next query`() {
      val content = "-- name: a :one\nSELECT 1;\n/*\n-- doc\n*/\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result[1].comments).isEmpty()
      assertThat(result[1].sourceLine).isEqualTo(6)
    }

    @Test
    fun `a doc comment after a block comment between queries is kept`() {
      val content = "-- name: a :one\nSELECT 1;\n/*\nfoo\n*/\n-- Doc.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(7)
    }

    @Test
    fun `a block comment holding SQL between queries hides it`() {
      val content =
        "-- name: a :one\nSELECT 1;\n\n/*\nSELECT x /* old note */\nFROM y;\n*/\n-- name: b :one\nSELECT 2;\n"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(8)
    }

    @Test
    fun `a block comment closed at the end of the file is accepted`() {
      val result = QueryFileParser.parse("-- name: a :one\nSELECT 1;\n/* /* */ */")

      assertThat(result.map { it.name }).containsExactly("a")
    }

    @Test
    fun `a block comment between queries clears the doc comments before it`() {
      val content = "-- name: a :one\nSELECT 1;\n-- doc\n/* x */\n-- name: b :one\nSELECT 2;"

      assertThat(QueryFileParser.parse(content)[1].comments).isEmpty()
    }

    @Test
    fun `doc comments after the terminator with blank lines between them are kept`() {
      val content = "-- name: a :one\nSELECT 1;\n\n-- First.\n\n-- Second.\n-- name: b :one\nSELECT 2;"

      assertThat(QueryFileParser.parse(content)[1].comments).containsExactly("First.", "Second.")
    }

    @Test
    fun `a doc comment line in an unterminated body stays in that body`() {
      val content = "-- name: a :one\nSELECT 1\n-- doc\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 1\n-- doc")
      assertThat(result[1].comments).isEmpty()
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `a line holding only a semicolon between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `a line holding a semicolon and a comment between queries is accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n; -- nothing\n\n-- Doc b.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).containsExactly("Doc b.")
      assertThat(result[1].sourceLine).isEqualTo(6)
    }

    @Test
    fun `lines holding only semicolons between queries are accepted`() {
      val content = "-- name: a :one\nSELECT 1;\n;;\n ; ;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.sql }).containsExactly("SELECT 1", "SELECT 2")
      assertThat(result[1].comments).isEmpty()
    }

    @Test
    fun `a semicolon line clears the doc comments before it`() {
      val content = "-- name: a :one\nSELECT 1;\n-- doc\n;\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].comments).isEmpty()
    }

    @Test
    fun `a statement after a block comment that starts on the terminator line throws`() {
      val content = "-- name: a :one\nSELECT 1; /* x\n */ SELECT 2;"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 1")
    }

    @Test
    fun `SQL between queries throws naming the query`() {
      val content = "-- name: a :one\nSELECT 1;\n\nstray words\n-- name: b :one\nSELECT 2;"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 1")
      exception.messageContains("line 4")
    }

    @Test
    fun `block comments before the first annotation are accepted and clear earlier doc comments`() {
      val content = "-- stale\n/* Generated */\n-- Doc.\n-- name: a :one\nSELECT 1;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].comments).containsExactly("Doc.")
      assertThat(result[0].sourceLine).isEqualTo(4)
    }

    @Test
    fun `SQL before the first annotation clears earlier doc comments and does not throw`() {
      val result = QueryFileParser.parse("-- stale\nGenerated by hand\n-- name: a :one\nSELECT 1;")

      assertThat(result[0].comments).isEmpty()
      assertThat(result[0].sql).isEqualTo("SELECT 1")
    }

    @Test
    fun `a non-breaking space after the terminator is whitespace`() {
      val content = "-- name: a :one\nSELECT 1;\u00A0\n-- Doc b.\n-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[0].sql).isEqualTo("SELECT 1")
      assertThat(result[1].comments).containsExactly("Doc b.")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `an annotation and a doc comment indented with a non-breaking space are recognized`() {
      val content = "-- name: a :one\nSELECT 1;\n\u00A0-- Doc.\n\u00A0-- name: b :one\nSELECT 2;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a", "b")
      assertThat(result[1].comments).containsExactly("Doc.")
      assertThat(result[1].sourceLine).isEqualTo(4)
    }

    @Test
    fun `a first annotation indented with a non-breaking space is recognized`() {
      val content = "\u00A0-- Doc.\n\u00A0-- name: a :one\nSELECT 1;"

      val result = QueryFileParser.parse(content)

      assertThat(result.map { it.name }).containsExactly("a")
      assertThat(result[0].comments).containsExactly("Doc.")
      assertThat(result[0].sourceLine).isEqualTo(2)
    }

    @Test
    fun `a non-breaking space before the first annotation does not clear the doc comments`() {
      val result = QueryFileParser.parse("-- Doc.\n\u00A0\n-- name: a :one\nSELECT 1;")

      assertThat(result[0].comments).containsExactly("Doc.")
    }

    @Test
    fun `a semicolon before the first annotation is accepted`() {
      val result = QueryFileParser.parse(";\n-- name: a :one\nSELECT 1;")

      assertThat(result.map { it.name }).containsExactly("a")
    }
  }

  @Nested
  inner class LexerErrors {

    @Test
    fun `an unterminated quoted identifier in a body names the query and the line`() {
      val content = "-- name: a :one\nSELECT 1;\n\n-- name: broken :one\nSELECT \"oops;\n"

      val exception = assertFailure { QueryFileParser.parse(content) }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("broken")
      exception.messageContains("line 4")
      exception.messageContains("line 5")
    }

    @Test
    fun `an unterminated string after the terminator names the query and the line`() {
      val exception =
        assertFailure { QueryFileParser.parse("-- name: a :one\nSELECT 1;\n'oops\n-- name: b :one\nSELECT 2;") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 3")
    }

    @Test
    fun `an unterminated block comment between queries names the query and the line`() {
      val exception =
        assertFailure { QueryFileParser.parse("-- name: a :one\nSELECT 1;\n/* oops\n-- name: b :one\nSELECT 2;") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("'a'")
      exception.messageContains("line 3")
    }

    @Test
    fun `an unterminated string before the first annotation throws naming the line`() {
      val exception = assertFailure { QueryFileParser.parse("-- note\n'oops\n-- name: a :one\nSELECT 1;") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("before the first annotation")
      exception.messageContains("line 2")
    }

    @Test
    fun `an unterminated dollar quote in a file with no annotation throws`() {
      val exception = assertFailure { QueryFileParser.parse("${'$'}${'$'} oops") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("before the first annotation")
    }

    @Test
    fun `a NUL character before the first annotation throws naming the line`() {
      val exception = assertFailure { QueryFileParser.parse("-- note\n\u0000\n-- name: a :one\nSELECT 1;") }

      exception.isInstanceOf(IllegalArgumentException::class)
      exception.messageContains("before the first annotation")
      exception.messageContains("NUL")
      exception.messageContains("line 2")
    }
  }
}
