package norm.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.asTypeName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import plugin.Schema
import plugin.Table

class SqlStatementTest {

  @Nested
  inner class ResultRowShape {

    @Test
    fun `query has no return`() {
      val statement = createStatement(
        "CALL my_procedure();",
        cmd = ":exec",
      )
      assertThat(statement.resultRowShape.kotlinType).isNull()
      assertThat(statement.resultRowShape.builder).isEmpty()
      assertThat(statement.resultRowShape.creationParameters).isEmpty()
    }

    @Test
    fun `query returns single column`() {
      val statement = createStatement(
        "SELECT name FROM author;",
        columns = listOf(column("name")),
      )
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(String::class.asTypeName())
      assertThat(statement.resultRowShape.builder).hasSize(1)
      assertThat(statement.resultRowShape.creationParameters).hasSize(1)
      assertThat(statement.resultRowShape.isComposedOfMultipleColumns).isFalse()
    }

    @Test
    fun `query returns single nullable column`() {
      val statement = createStatement(
        "SELECT middle_name FROM author;",
        columns = listOf(column("middle_name", notNull = false)),
      )
      val kotlinType = statement.resultRowShape.kotlinType!!
      assertThat(kotlinType.isNullable).isTrue()
      assertThat(kotlinType).isEqualTo(String::class.asTypeName().copy(nullable = true))
    }

    @Test
    fun `query returns single table star projection`() {
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("name"),
          column("email"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT * FROM author;",
        columns = listOf(
          column("name", table = Identifier(name = "author")),
          column("email", table = Identifier(name = "author")),
        ),
        catalog = catalog,
      )

      // Star projection returns the table model class
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "Author"))
      assertThat(statement.resultRowShape.isComposedOfMultipleColumns).isTrue()
    }

    @Test
    fun `query returns partial table projection`() {
      // Table has 3 columns, query returns only 2
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
          column("email"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT name, email FROM author;",
        name = "PartialAuthor",
        columns = listOf(
          column("name", table = Identifier(name = "author")),
          column("email", table = Identifier(name = "author")),
        ),
        catalog = catalog,
      )

      // Partial projection creates ad-hoc type named after query
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "PartialAuthor"))
      assertThat(statement.resultRowShape.creationParameters).hasSize(2)
    }

    @Test
    fun `partial projection detected even when all columns have same type`() {
      // Regression test: previously isSingleTableStarProjection compared by Column::type
      // which caused partial projections to be incorrectly detected as star projections
      // when all columns had the same type
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("name"),
          column("email"),
          column("bio"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT name, email FROM author;",
        name = "PartialAuthor",
        columns = listOf(
          column("name", table = Identifier(name = "author")),
          column("email", table = Identifier(name = "author")),
        ),
        catalog = catalog,
      )

      // Should create ad-hoc type, not reuse the table model
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "PartialAuthor"))
      assertThat(statement.resultRowShape.creationParameters).hasSize(2)
    }

    @Test
    fun `query returns columns from multiple tables`() {
      val statement = createStatement(
        "SELECT a.name, b.title FROM author a JOIN book b ON a.id = b.author_id;",
        name = "AuthorBook",
        columns = listOf(
          column("name", table = Identifier(name = "author")),
          column("title", table = Identifier(name = "book")),
        ),
      )

      // Multi-table creates ad-hoc type named after query
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "AuthorBook"))
      assertThat(statement.resultRowShape.creationParameters).hasSize(2)
      assertThat(statement.resultRowShape.isComposedOfMultipleColumns).isTrue()
    }

    @Test
    fun `query with single embedded table via sqlc embed`() {
      // sqlc.embed() allows embedding an entire table as a nested object
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT sqlc.embed(author) FROM author;",
        name = "AuthorWithEmbed",
        columns = listOf(
          // embed_table indicates this column represents the embedded table
          column("author", embedTable = Identifier(name = "author")),
        ),
        catalog = catalog,
      )

      // Result type is ad-hoc class with embedded Author property
      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "AuthorWithEmbed"))
      // Creation parameters are flattened from embedded table (id, name)
      assertThat(statement.resultRowShape.creationParameters).hasSize(2)
      assertThat(statement.resultRowShape.builder).hasSize(2)
    }

    @Test
    fun `query with regular column and embedded table`() {
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT b.title, sqlc.embed(author) FROM book b JOIN author ON b.author_id = author.id;",
        name = "BookWithAuthor",
        columns = listOf(
          column("title"),
          column("author", embedTable = Identifier(name = "author")),
        ),
        catalog = catalog,
      )

      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "BookWithAuthor"))
      // 1 regular column (title) + 2 from embedded author (id, name)
      assertThat(statement.resultRowShape.creationParameters).hasSize(3)
      assertThat(statement.resultRowShape.builder).hasSize(3)
    }

    @Test
    fun `query with multiple embedded tables`() {
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val bookTable = Table(
        Identifier(name = "book"),
        columns = listOf(
          column("id", type = "int4"),
          column("title"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable, bookTable))))

      val statement = createStatement(
        "SELECT sqlc.embed(author), sqlc.embed(book) FROM author JOIN book ON author.id = book.author_id;",
        name = "AuthorAndBook",
        columns = listOf(
          column("author", embedTable = Identifier(name = "author")),
          column("book", embedTable = Identifier(name = "book")),
        ),
        catalog = catalog,
      )

      assertThat(statement.resultRowShape.kotlinType).isEqualTo(ClassName("test", "AuthorAndBook"))
      // 2 from author (id, name) + 2 from book (id, title)
      assertThat(statement.resultRowShape.creationParameters).hasSize(4)
      assertThat(statement.resultRowShape.builder).hasSize(4)
    }
  }

  @Nested
  inner class Parameters {

    @Test
    fun `query with no parameters`() {
      val statement = createStatement("SELECT * FROM author;")
      assertThat(statement.parameters).isEmpty()
    }

    @Test
    fun `query with single parameter`() {
      val statement = createStatement(
        $$"SELECT * FROM author WHERE id = $1;",
        params = listOf(param(1, "id", "int4")),
      )
      assertThat(statement.parameters).hasSize(1)
      assertThat(statement.parameters[0].number).isEqualTo(1)
    }

    @Test
    fun `query with multiple parameters in order`() {
      val statement = createStatement(
        $$"INSERT INTO author (name, email, bio) VALUES ($1, $2, $3);",
        cmd = ":exec",
        params = listOf(
          param(1, "name"),
          param(2, "email"),
          param(3, "bio"),
        ),
      )
      assertThat(statement.parameters).hasSize(3)
      assertThat(statement.parameters.map { it.number }).containsExactly(1, 2, 3)
    }

    @Test
    fun `query with non-sequential parameter references`() {
      // SQL uses $3, $1, $2 but params are defined 1, 2, 3
      val statement = createStatement(
        $$"SELECT * FROM t WHERE c = $3 AND a = $1 AND b = $2;",
        params = listOf(
          param(1, "a"),
          param(2, "b"),
          param(3, "c"),
        ),
      )
      // Parameters should be in placeholder order as they appear in SQL
      assertThat(statement.parameters.map { it.number }).containsExactly(3, 1, 2)
    }

    @Test
    fun `parameter referenced multiple times`() {
      val param1 = param(1, "id", "int4")
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 OR b = $1;",
        params = listOf(param1),
      )
      // Same parameter appears twice
      assertThat(statement.parameters).hasSize(2)
      assertThat(statement.parameters[0]).isSameInstanceAs(param1)
      assertThat(statement.parameters[1]).isSameInstanceAs(param1)
    }
  }

  @Nested
  inner class SqlTextConversion {

    @Test
    fun `single placeholder converted to question mark`() {
      val statement = createStatement(
        $$"SELECT * FROM author WHERE id = $1;",
        params = listOf(param(1)),
      )
      assertThat(statement.sql).isEqualTo("SELECT * FROM author WHERE id = ?;")
    }

    @Test
    fun `multiple placeholders converted`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3;",
        params = listOf(param(1), param(2), param(3)),
      )
      assertThat(statement.sql).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ? AND c = ?;")
    }

    @Test
    fun `no placeholders unchanged`() {
      val statement = createStatement("SELECT * FROM author;")
      assertThat(statement.sql).isEqualTo("SELECT * FROM author;")
    }

    @Test
    fun `double digit placeholder converted`() {
      val params = (1..12).map { param(it) }
      val statement = createStatement(
        $$"INSERT INTO t VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12);",
        cmd = ":exec",
        params = params,
      )
      assertThat(statement.sql).isEqualTo("INSERT INTO t VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")
    }
  }

  @Nested
  inner class CommandProperty {

    @Test
    fun `command one`() {
      val statement = createStatement("SELECT * FROM t LIMIT 1;", cmd = ":one")
      assertThat(statement.command).isEqualTo(Command.ONE)
    }

    @Test
    fun `command many`() {
      val statement = createStatement("SELECT * FROM t;", cmd = ":many")
      assertThat(statement.command).isEqualTo(Command.MANY)
    }

    @Test
    fun `command exec`() {
      val statement = createStatement("DELETE FROM t;", cmd = ":exec")
      assertThat(statement.command).isEqualTo(Command.EXEC)
    }

    @Test
    fun `command execrows`() {
      val statement = createStatement("UPDATE t SET x = 1;", cmd = ":execrows")
      assertThat(statement.command).isEqualTo(Command.EXEC_ROWS)
    }
  }

  @Nested
  inner class CanBeBatched {

    @Test
    fun `SELECT cannot be batched`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE id = $1;",
        cmd = ":one",
        params = listOf(param(1)),
      )
      assertThat(statement.canBeBatched).isFalse()
    }

    @Test
    fun `MANY cannot be batched`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE status = $1;",
        cmd = ":many",
        params = listOf(param(1)),
      )
      assertThat(statement.canBeBatched).isFalse()
    }

    @Test
    fun `DML without params cannot be batched`() {
      val statement = createStatement(
        "DELETE FROM t WHERE status = 'old';",
        cmd = ":exec",
      )
      assertThat(statement.canBeBatched).isFalse()
    }

    @Test
    fun `DML with RETURNING cannot be batched`() {
      val statement = createStatement(
        $$"INSERT INTO t (name) VALUES ($1) RETURNING id;",
        cmd = ":exec",
        params = listOf(param(1)),
        columns = listOf(column("id", type = "int4")),
      )
      assertThat(statement.canBeBatched).isFalse()
    }

    @Test
    fun `DML with params and no RETURNING can be batched`() {
      val statement = createStatement(
        $$"INSERT INTO t (name) VALUES ($1);",
        cmd = ":exec",
        params = listOf(param(1)),
      )
      assertThat(statement.canBeBatched).isTrue()
    }

    @Test
    fun `execrows with params can be batched`() {
      val statement = createStatement(
        $$"UPDATE t SET status = 'done' WHERE id = $1;",
        cmd = ":execrows",
        params = listOf(param(1)),
      )
      assertThat(statement.canBeBatched).isTrue()
    }
  }

  @Nested
  inner class CanBeDynamic {

    @Test
    fun `MANY without params can be dynamic`() {
      val statement = createStatement(
        "SELECT * FROM t;",
        cmd = ":many",
        columns = listOf(column("id")),
      )
      assertThat(statement.canBeDynamic).isTrue()
    }

    @Test
    fun `MANY with params cannot be dynamic`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE status = $1;",
        cmd = ":many",
        params = listOf(param(1)),
        columns = listOf(column("id")),
      )
      assertThat(statement.canBeDynamic).isFalse()
    }

    @Test
    fun `ONE cannot be dynamic`() {
      val statement = createStatement(
        "SELECT * FROM t LIMIT 1;",
        cmd = ":one",
        columns = listOf(column("id")),
      )
      assertThat(statement.canBeDynamic).isFalse()
    }

    @Test
    fun `EXEC cannot be dynamic`() {
      val statement = createStatement(
        "DELETE FROM t;",
        cmd = ":exec",
      )
      assertThat(statement.canBeDynamic).isFalse()
    }

    @Test
    fun `EXECROWS cannot be dynamic`() {
      val statement = createStatement(
        "UPDATE t SET x = 1;",
        cmd = ":execrows",
      )
      assertThat(statement.canBeDynamic).isFalse()
    }
  }

  @Nested
  inner class NameAndComments {

    @Test
    fun `name from query`() {
      val statement = createStatement(
        "SELECT * FROM t;",
        name = "GetAllUsers",
      )
      assertThat(statement.name).isEqualTo("GetAllUsers")
    }

    @Test
    fun `comments from query`() {
      val statement = createStatement(
        "SELECT * FROM t;",
        comments = listOf("Fetches all users", "Used by admin panel"),
      )
      assertThat(statement.comments).containsExactly("Fetches all users", "Used by admin panel")
    }

    @Test
    fun `empty comments list`() {
      val statement = createStatement("SELECT * FROM t;")
      assertThat(statement.comments).isEmpty()
    }
  }

  @Nested
  inner class BuilderContent {

    @Test
    fun `single varchar column generates getString accessor`() {
      val statement = createStatement(
        "SELECT name FROM person;",
        columns = listOf(column("name", type = "varchar")),
      )

      assertThat(statement.resultRowShape.builder).hasSize(1)
      assertThat(statement.resultRowShape.builder[0].toString()).contains("getString(1)")
    }

    @Test
    fun `single int column generates getInt accessor`() {
      val statement = createStatement(
        "SELECT id FROM person;",
        columns = listOf(column("id", type = "int4")),
      )

      assertThat(statement.resultRowShape.builder).hasSize(1)
      assertThat(statement.resultRowShape.builder[0].toString()).contains("getInt(1)")
    }

    @Test
    fun `column indices match position in result set`() {
      val statement = createStatement(
        "SELECT id, name, email FROM person;",
        name = "Person",
        columns = listOf(
          column("id", type = "int4"),
          column("name", type = "varchar"),
          column("email", type = "varchar"),
        ),
      )

      val builders = statement.resultRowShape.builder
      assertThat(builders).hasSize(3)
      // ResultSet indices are 1-based and match column order
      assertThat(builders[0].toString()).contains("(1)")
      assertThat(builders[1].toString()).contains("(2)")
      assertThat(builders[2].toString()).contains("(3)")
    }

    @Test
    fun `different types generate appropriate accessors`() {
      val statement = createStatement(
        "SELECT id, price, active, name FROM product;",
        name = "Product",
        columns = listOf(
          column("id", type = "int8"),
          column("price", type = "float8"),
          column("active", type = "bool"),
          column("name", type = "text"),
        ),
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      assertThat(builders[0]).contains("getLong")
      assertThat(builders[1]).contains("getDouble")
      assertThat(builders[2]).contains("getBoolean")
      assertThat(builders[3]).contains("getString")
    }
  }

  // Helper to create SqlStatement with common defaults
  private fun createStatement(
    sql: String,
    cmd: String = ":one",
    name: String = "TestQuery",
    columns: List<Column> = emptyList(),
    params: List<Parameter> = emptyList(),
    catalog: Catalog = Catalog(),
    comments: List<String> = emptyList(),
  ): SqlStatement {
    val repository = TypeRepository("test", catalog)
    return SqlStatement(
      catalog,
      Query(
        text = sql,
        cmd = cmd,
        name = name,
        columns = columns,
        params = params,
        comments = comments,
      ),
      repository,
    )
  }

  // Helper to create a column with common defaults
  private fun column(
    name: String,
    type: String = "varchar",
    notNull: Boolean = true,
    isArray: Boolean = false,
    table: Identifier? = null,
    embedTable: Identifier? = null,
  ) = Column(
    name = name,
    not_null = notNull,
    type = Identifier(name = type),
    is_array = isArray,
    table = table,
    embed_table = embedTable,
  )

  // Helper to create a parameter
  private fun param(number: Int, name: String = "p$number", type: String = "varchar") =
    Parameter(number = number, column = Column(name = name, type = Identifier(name = type)))
}
