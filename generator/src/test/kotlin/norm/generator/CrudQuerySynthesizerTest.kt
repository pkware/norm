package norm.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.extracting
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.none
import org.junit.jupiter.api.Test
import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Schema
import plugin.Table

class CrudQuerySynthesizerTest {

  @Test
  fun `generates all CRUD methods for table with serial PK`() {
    val table = table(
      "author",
      column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
      column("name", "text", notNull = true),
      column("bio", "text"),
      column("created_at", "timestamptz", notNull = true, hasDefault = true),
    )
    val catalog = catalog(table)

    val queries = CrudQuerySynthesizer.synthesize(catalog)

    assertThat(queries).extracting(ParsedQuery::name).containsExactly(
      "insertAuthor",
      "findAuthorById",
      "existsAuthorById",
      "deleteAuthorById",
      "findAllAuthor",
      "countAuthor",
      "deleteAllAuthor",
    )
  }

  @Test
  fun `insert excludes auto-increment, default, and generated columns`() {
    val table = table(
      "product",
      column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
      column("name", "text", notNull = true),
      column("price", "numeric", notNull = true),
      column("total", "numeric", isGenerated = true),
    )
    val catalog = catalog(table)

    val insert = CrudQuerySynthesizer.synthesize(catalog).first { it.name == "insertProduct" }

    assertThat(insert.sql).isEqualTo("INSERT INTO product (name, price) VALUES (?, ?) RETURNING id, total")
    assertThat(insert.command).isEqualTo(":one")
  }

  @Test
  fun `insert uses exec when no columns are excluded`() {
    val table = table(
      "order_item",
      column("order_id", "int4", notNull = true, isPrimaryKey = true),
      column("item_id", "int4", notNull = true, isPrimaryKey = true),
      column("quantity", "int4", notNull = true),
    )
    val catalog = catalog(table)

    val insert = CrudQuerySynthesizer.synthesize(catalog).first { it.name == "insertOrderItem" }

    assertThat(insert.sql).isEqualTo("INSERT INTO order_item (order_id, item_id, quantity) VALUES (?, ?, ?)")
    assertThat(insert.command).isEqualTo(":exec")
  }

  @Test
  fun `skips insert when all columns are excluded`() {
    val table = table(
      "auto_table",
      column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
      column("created_at", "timestamptz", notNull = true, hasDefault = true),
    )
    val catalog = catalog(table)

    val queries = CrudQuerySynthesizer.synthesize(catalog)

    assertThat(queries.map { it.name }).none { it.isEqualTo("insertAutoTable") }
  }

  @Test
  fun `generates composite PK methods with multiple parameters`() {
    val table = table(
      "order_item",
      column("order_id", "int4", notNull = true, isPrimaryKey = true),
      column("item_id", "int4", notNull = true, isPrimaryKey = true),
      column("quantity", "int4", notNull = true),
    )
    val catalog = catalog(table)

    val findById = CrudQuerySynthesizer.synthesize(catalog).first { it.name == "findOrderItemById" }

    assertThat(findById.sql).isEqualTo("SELECT * FROM order_item WHERE order_id = ? AND item_id = ?")
    assertThat(findById.command).isEqualTo(":many")
  }

  @Test
  fun `skips PK-dependent methods when table has no PK`() {
    val table = table(
      "audit_log",
      column("message", "text", notNull = true),
      column("logged_at", "timestamptz", notNull = true, hasDefault = true),
    )
    val catalog = catalog(table)

    val queries = CrudQuerySynthesizer.synthesize(catalog)

    assertThat(queries).extracting(ParsedQuery::name).containsExactly(
      "insertAuditLog",
      "findAllAuditLog",
      "countAuditLog",
      "deleteAllAuditLog",
    )
  }

  @Test
  fun `skips views entirely`() {
    val view = Table(
      rel = Identifier(name = "author_names", schema = "public"),
      columns = listOf(column("id", "int4", notNull = true, isPrimaryKey = true)),
      is_view = true,
    )
    val catalog = catalog(view)

    val queries = CrudQuerySynthesizer.synthesize(catalog)

    assertThat(queries).isEmpty()
  }

  @Test
  fun `handles snake_case table names`() {
    val table = table(
      "order_item",
      column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
      column("name", "text", notNull = true),
    )
    val catalog = catalog(table)

    val queries = CrudQuerySynthesizer.synthesize(catalog)

    assertThat(queries).extracting(ParsedQuery::name).isNotEmpty()
    assertThat(queries.first { it.name.startsWith("find") && it.name.endsWith("ById") }.name)
      .isEqualTo("findOrderItemById")
  }

  @Test
  fun `qualifies table name for non-public schemas`() {
    val table = Table(
      rel = Identifier(name = "event", schema = "analytics"),
      columns = listOf(
        column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
        column("data", "text", notNull = true),
      ),
    )
    val catalog = Catalog(
      default_schema = "analytics",
      schemas = listOf(Schema(name = "analytics", tables = listOf(table))),
    )

    val findAll = CrudQuerySynthesizer.synthesize(catalog).first { it.name == "findAllEvent" }

    assertThat(findAll.sql).isEqualTo("SELECT * FROM analytics.event")
  }

  @Test
  fun `synthesizeAndMerge gives user queries priority over synthetic ones`() {
    val table = table(
      "author",
      column("id", "int4", notNull = true, isPrimaryKey = true, isAutoIncrement = true),
      column("name", "text", notNull = true),
    )
    val catalog = catalog(table)
    val userFindAll = ParsedQuery(
      name = "findAllAuthor",
      command = ":many",
      sql = "SELECT name FROM author",
      comments = emptyList(),
    )

    val merged = CrudQuerySynthesizer.synthesizeAndMerge(catalog, listOf(userFindAll))

    // User query appears first and is kept; synthetic findAllAuthor is discarded
    assertThat(merged.first { it.name == "findAllAuthor" }.sql).isEqualTo("SELECT name FROM author")
    // Only one findAllAuthor in the list
    assertThat(merged.count { it.name == "findAllAuthor" }).isEqualTo(1)
    // Other CRUD methods are still present
    assertThat(merged.map { it.name }).containsExactly(
      "findAllAuthor",
      "insertAuthor",
      "findAuthorById",
      "existsAuthorById",
      "deleteAuthorById",
      "countAuthor",
      "deleteAllAuthor",
    )
  }

  // --- Helpers ---

  private fun column(
    name: String,
    typeName: String,
    notNull: Boolean = false,
    isPrimaryKey: Boolean = false,
    isAutoIncrement: Boolean = false,
    hasDefault: Boolean = false,
    isGenerated: Boolean = false,
  ) = Column(
    name = name,
    not_null = notNull,
    type = Identifier(name = typeName),
    table = Identifier(name = ""),
    original_name = name,
    is_primary_key = isPrimaryKey,
    is_auto_increment = isAutoIncrement,
    has_default = hasDefault,
    is_generated = isGenerated,
  )

  private fun table(name: String, vararg columns: Column) = Table(
    rel = Identifier(name = name, schema = "public"),
    columns = columns.toList(),
  )

  private fun catalog(vararg tables: Table) = Catalog(
    default_schema = "public",
    schemas = listOf(Schema(name = "public", tables = tables.toList())),
  )
}
