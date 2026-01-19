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

    @Test
    fun `query with regular column before and after embedded table`() {
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable))))

      val statement = createStatement(
        "SELECT b.id, sqlc.embed(author), b.published_year FROM book b JOIN author ON b.author_id = author.id;",
        name = "BookWithAuthorAndColumns",
        columns = listOf(
          column("id", type = "int4"), // Index should be 1
          column("author", embedTable = Identifier(name = "author")), // Indices should be 2-3
          column("published_year", type = "int4"), // Index should be 4
        ),
        catalog = catalog,
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      // Expected indices: 1 (id), 2-3 (author.id, author.name), 4 (published_year)
      assertThat(builders).hasSize(4)
      assertThat(builders[0]).contains("getInt(1)") // b.id
      assertThat(builders[1]).contains("getInt(2)") // author.id
      assertThat(builders[2]).contains("getString(3)") // author.name
      // BUG LIKELY HERE: published_year should use index 4, but may use wrong index
      assertThat(builders[3]).contains("getInt(4)") // b.published_year
    }

    @Test
    fun `query with three consecutive embedded tables`() {
      // Test cumulative index offset errors with multiple consecutive embeds
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val publisherTable = Table(
        Identifier(name = "publisher"),
        columns = listOf(
          column("id", type = "int4"),
          column("company_name"),
          column("country"),
        ),
      )
      val reviewerTable = Table(
        Identifier(name = "reviewer"),
        columns = listOf(
          column("id", type = "int4"),
          column("reviewer_name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable, publisherTable, reviewerTable))))

      val statement = createStatement(
        "SELECT sqlc.embed(author), sqlc.embed(publisher), sqlc.embed(reviewer) FROM book;",
        name = "ThreeEmbeds",
        columns = listOf(
          column("author", embedTable = Identifier(name = "author")), // Indices 1-2
          column("publisher", embedTable = Identifier(name = "publisher")), // Indices 3-5
          column("reviewer", embedTable = Identifier(name = "reviewer")), // Indices 6-7
        ),
        catalog = catalog,
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      // Expected: 2 (author) + 3 (publisher) + 2 (reviewer) = 7 total indices
      assertThat(builders).hasSize(7)
      // Author columns
      assertThat(builders[0]).contains("getInt(1)")
      assertThat(builders[1]).contains("getString(2)")
      // Publisher columns - BUG LIKELY HERE: may start at wrong index
      assertThat(builders[2]).contains("getInt(3)")
      assertThat(builders[3]).contains("getString(4)")
      assertThat(builders[4]).contains("getString(5)")
      // Reviewer columns - BUG LIKELY HERE: cumulative offset error
      assertThat(builders[5]).contains("getInt(6)")
      assertThat(builders[6]).contains("getString(7)")
    }

    @Test
    fun `query with regular columns surrounding embed`() {
      // Test the "sandwich" pattern: regular columns on both sides of multi-column embed
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
        "SELECT b.title, b.isbn, sqlc.embed(author), b.page_count, b.price FROM book b JOIN author;",
        name = "SandwichBook",
        columns = listOf(
          column("title"), // Index 1
          column("isbn"), // Index 2
          column("author", embedTable = Identifier(name = "author")), // Indices 3-5
          column("page_count", type = "int4"), // Index 6
          column("price", type = "float8"), // Index 7
        ),
        catalog = catalog,
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      // Expected: 2 regular + 3 (author) + 2 regular = 7 total
      assertThat(builders).hasSize(7)
      assertThat(builders[0]).contains("getString(1)") // title
      assertThat(builders[1]).contains("getString(2)") // isbn
      assertThat(builders[2]).contains("getInt(3)") // author.id
      assertThat(builders[3]).contains("getString(4)") // author.name
      assertThat(builders[4]).contains("getString(5)") // author.email
      // BUG LIKELY HERE: indices 6-7 after 3-column embed
      assertThat(builders[5]).contains("getInt(6)") // page_count
      assertThat(builders[6]).contains("getDouble(7)") // price
    }

    @Test
    fun `query with single column embedded table followed by regular columns`() {
      // Edge case: test if bug is specific to multi-column embeds
      val categoryTable = Table(
        Identifier(name = "category"),
        columns = listOf(
          column("name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(categoryTable))))

      val statement = createStatement(
        "SELECT sqlc.embed(category), b.title, b.id FROM book b JOIN category;",
        name = "SingleColumnEmbed",
        columns = listOf(
          column("category", embedTable = Identifier(name = "category")), // Index 1
          column("title"), // Index 2
          column("id", type = "int4"), // Index 3
        ),
        catalog = catalog,
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      assertThat(builders).hasSize(3)
      assertThat(builders[0]).contains("getString(1)") // category.name
      assertThat(builders[1]).contains("getString(2)") // title
      assertThat(builders[2]).contains("getInt(3)") // id
    }

    @Test
    fun `query with alternating regular and embed columns`() {
      // Most complex: multiple alternations between regular and embed columns
      val authorTable = Table(
        Identifier(name = "author"),
        columns = listOf(
          column("id", type = "int4"),
          column("name"),
        ),
      )
      val publisherTable = Table(
        Identifier(name = "publisher"),
        columns = listOf(
          column("id", type = "int4"),
          column("company_name"),
        ),
      )
      val catalog = Catalog(schemas = listOf(Schema(tables = listOf(authorTable, publisherTable))))

      val statement = createStatement(
        "SELECT b.title, sqlc.embed(author), b.isbn, sqlc.embed(publisher), b.year FROM book b;",
        name = "AlternatingPattern",
        columns = listOf(
          column("title"), // Index 1
          column("author", embedTable = Identifier(name = "author")), // Indices 2-3
          column("isbn"), // Index 4
          column("publisher", embedTable = Identifier(name = "publisher")), // Indices 5-6
          column("year", type = "int4"), // Index 7
        ),
        catalog = catalog,
      )

      val builders = statement.resultRowShape.builder.map { it.toString() }
      assertThat(builders).hasSize(7)
      assertThat(builders[0]).contains("getString(1)") // title
      assertThat(builders[1]).contains("getInt(2)") // author.id
      assertThat(builders[2]).contains("getString(3)") // author.name
      // BUG LIKELY HERE: isbn after first embed
      assertThat(builders[3]).contains("getString(4)") // isbn
      assertThat(builders[4]).contains("getInt(5)") // publisher.id
      assertThat(builders[5]).contains("getString(6)") // publisher.company_name
      // BUG LIKELY HERE: year after second embed
      assertThat(builders[6]).contains("getInt(7)") // year
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

  @Nested
  inner class ParameterNameDeduplication {

    @Test
    fun `parameters with unique names are unchanged`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3;",
        params = listOf(
          param(1, "id", "int4"),
          param(2, "name", "text"),
          param(3, "email", "text"),
        ),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("id")
      assertThat(statement.getParameterName(1)).isEqualTo("name")
      assertThat(statement.getParameterName(2)).isEqualTo("email")
    }

    @Test
    fun `two duplicate names get deduplicated`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2;",
        params = listOf(
          param(1, "value", "text"),
          param(2, "value", "text"),
        ),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("value")
      assertThat(statement.getParameterName(1)).isEqualTo("value2")
    }

    @Test
    fun `three duplicate names get deduplicated`() {
      val statement = createStatement(
        $$"SELECT * FROM normal_rand($1, $2, $3);",
        cmd = ":many",
        params = listOf(
          param(1, "normal_rand", "int4"),
          param(2, "normal_rand", "float8"),
          param(3, "normal_rand", "float8"),
        ),
        columns = listOf(column("normal_rand", type = "float8")),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("normal_rand")
      assertThat(statement.getParameterName(1)).isEqualTo("normal_rand2")
      assertThat(statement.getParameterName(2)).isEqualTo("normal_rand3")
    }

    @Test
    fun `mixed unique and duplicate names`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3 AND d = $4 AND e = $5;",
        params = listOf(
          param(1, "id", "int4"),
          param(2, "value", "text"),
          param(3, "value", "text"),
          param(4, "name", "text"),
          param(5, "value", "text"),
        ),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("id")
      assertThat(statement.getParameterName(1)).isEqualTo("value")
      assertThat(statement.getParameterName(2)).isEqualTo("value2")
      assertThat(statement.getParameterName(3)).isEqualTo("name")
      assertThat(statement.getParameterName(4)).isEqualTo("value3")
    }

    @Test
    fun `empty parameter list`() {
      val statement = createStatement("SELECT * FROM t;")

      // Out of bounds on empty list should return fallback
      assertThat(statement.getParameterName(0)).isEqualTo("param0")
    }

    @Test
    fun `out of bounds index returns fallback`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2;",
        params = listOf(
          param(1, "id", "int4"),
          param(2, "name", "text"),
        ),
      )

      assertThat(statement.getParameterName(5)).isEqualTo("param5")
    }

    @Test
    fun `negative index returns fallback`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2;",
        params = listOf(
          param(1, "id", "int4"),
          param(2, "name", "text"),
        ),
      )

      assertThat(statement.getParameterName(-1)).isEqualTo("param-1")
    }

    @Test
    fun `crosstab function with duplicate parameter names`() {
      val statement = createStatement(
        $$"SELECT * FROM crosstab($1, $2) AS ct(user_id int, setting1 text, setting2 text);",
        cmd = ":many",
        params = listOf(
          param(1, "crosstab", "text"),
          param(2, "crosstab", "text"),
        ),
        columns = listOf(
          column("user_id", type = "int4"),
          column("setting1", type = "text"),
          column("setting2", type = "text"),
        ),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("crosstab")
      assertThat(statement.getParameterName(1)).isEqualTo("crosstab2")
    }

    @Test
    fun `decode function with duplicate names`() {
      val statement = createStatement(
        $$"SELECT decode($1, $2) AS decoded;",
        params = listOf(
          param(1, "decode", "text"),
          param(2, "decode", "text"),
        ),
        columns = listOf(column("decoded", type = "bytea")),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("decode")
      assertThat(statement.getParameterName(1)).isEqualTo("decode2")
    }

    @Test
    fun `hmac function with three duplicate names`() {
      val statement = createStatement(
        $$"SELECT hmac($1, $2, $3) AS signature;",
        params = listOf(
          param(1, "hmac", "text"),
          param(2, "hmac", "text"),
          param(3, "hmac", "text"),
        ),
        columns = listOf(column("signature", type = "bytea")),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("hmac")
      assertThat(statement.getParameterName(1)).isEqualTo("hmac2")
      assertThat(statement.getParameterName(2)).isEqualTo("hmac3")
    }

    @Test
    fun `getParameterName with same column object multiple times`() {
      // Create a single Column object that will be reused
      val sharedColumn = Column(name = "value", type = Identifier(name = "text"))
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3;",
        params = listOf(
          Parameter(number = 1, column = sharedColumn),
          Parameter(number = 2, column = sharedColumn),
          Parameter(number = 3, column = sharedColumn),
        ),
      )

      // Even though it's the same Column object, each position should get a unique name
      assertThat(statement.getParameterName(0)).isEqualTo("value")
      assertThat(statement.getParameterName(1)).isEqualTo("value2")
      assertThat(statement.getParameterName(2)).isEqualTo("value3")
    }

    @Test
    fun `parameter name deduplication preserves parameter order`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3;",
        params = listOf(
          param(1, "value", "text"),
          param(2, "value", "text"),
          param(3, "value", "text"),
        ),
      )

      // Call getParameterName multiple times for same index - should return same result
      assertThat(statement.getParameterName(0)).isEqualTo("value")
      assertThat(statement.getParameterName(0)).isEqualTo("value")
      assertThat(statement.getParameterName(1)).isEqualTo("value2")
      assertThat(statement.getParameterName(1)).isEqualTo("value2")
      assertThat(statement.getParameterName(2)).isEqualTo("value3")
      assertThat(statement.getParameterName(2)).isEqualTo("value3")
    }

    @Test
    fun `four duplicate names get deduplicated correctly`() {
      val statement = createStatement(
        $$"SELECT * FROM t WHERE a = $1 AND b = $2 AND c = $3 AND d = $4;",
        params = listOf(
          param(1, "param", "text"),
          param(2, "param", "text"),
          param(3, "param", "text"),
          param(4, "param", "text"),
        ),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("param")
      assertThat(statement.getParameterName(1)).isEqualTo("param2")
      assertThat(statement.getParameterName(2)).isEqualTo("param3")
      assertThat(statement.getParameterName(3)).isEqualTo("param4")
    }

    @Test
    fun `digest function with duplicate names`() {
      val statement = createStatement(
        $$"SELECT digest($1, $2) AS hash;",
        params = listOf(
          param(1, "digest", "text"),
          param(2, "digest", "text"),
        ),
        columns = listOf(column("hash", type = "bytea")),
      )

      assertThat(statement.getParameterName(0)).isEqualTo("digest")
      assertThat(statement.getParameterName(1)).isEqualTo("digest2")
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
