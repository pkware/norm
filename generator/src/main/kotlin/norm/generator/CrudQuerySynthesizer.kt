package norm.generator

import plugin.Catalog
import plugin.Column
import plugin.Table

/**
 * Synthesizes CRUD [ParsedQuery] objects from a [Catalog].
 *
 * For each non-view table, generates repository-style methods (insert, find, exists, findAll,
 * count, delete, deleteAll). The resulting [ParsedQuery] objects are fed through the normal
 * [JdbcAnalyzer.analyzeQuery] pipeline, so they get the same type resolution, nullability
 * analysis, and projection generation as hand-written queries.
 */
public object CrudQuerySynthesizer {

  /**
   * Synthesizes CRUD queries for all non-view tables across all schemas, then merges them with
   * [userQueries]. User-defined queries take priority: if a user query has the same name as a
   * synthetic CRUD query, the synthetic one is discarded.
   *
   * @return The combined list with [userQueries] first, followed by non-conflicting CRUD queries.
   */
  public fun synthesizeAndMerge(catalog: Catalog, userQueries: List<ParsedQuery>): List<ParsedQuery> {
    val crudQueries = synthesize(catalog)
    val userQueryNames = userQueries.map { it.name }.toSet()
    return userQueries + crudQueries.filter { it.name !in userQueryNames }
  }

  /**
   * Synthesizes CRUD queries for all non-view tables across all schemas.
   *
   * @return A list of [ParsedQuery] objects ready for JDBC analysis.
   */
  public fun synthesize(catalog: Catalog): List<ParsedQuery> = buildList {
    for (schema in catalog.schemas) {
      for (table in schema.tables) {
        if (table.is_view) continue
        addAll(synthesizeForTable(table))
      }
    }
  }

  private fun synthesizeForTable(table: Table): List<ParsedQuery> {
    val tableName = table.rel?.name ?: return emptyList()
    val methodSuffix = tableName.snakeToCamelCase().titleCase()
    val qualifiedTable = qualifiedTableName(table)
    val primaryKeyColumns = table.columns.filter(Column::is_primary_key)
    val allColumns = table.columns

    return buildList {
      // INSERT — null when all columns are auto-increment, default, or generated
      synthesizeInsert(qualifiedTable, methodSuffix, allColumns)?.let(::add)

      // PK-dependent methods
      if (primaryKeyColumns.isNotEmpty()) {
        add(synthesizeFindById(qualifiedTable, methodSuffix, primaryKeyColumns))
        add(synthesizeExistsById(qualifiedTable, methodSuffix, primaryKeyColumns))
        add(synthesizeDeleteById(qualifiedTable, methodSuffix, primaryKeyColumns))
      }

      // PK-independent methods
      add(synthesizeFindAll(qualifiedTable, methodSuffix))
      add(synthesizeCount(qualifiedTable, methodSuffix))
      add(synthesizeDeleteAll(qualifiedTable, methodSuffix))
    }
  }

  /**
   * Generates an INSERT query. Columns that are auto-increment, have server defaults, or are
   * generated-always are excluded from the VALUES clause and included in a RETURNING clause.
   *
   * - If ALL columns are excluded (no insertable columns), `null` is returned (skip the method).
   * - If NO columns are excluded (nothing for RETURNING), the command is `:exec` instead of `:one`.
   */
  private fun synthesizeInsert(qualifiedTable: String, methodSuffix: String, allColumns: List<Column>): ParsedQuery? {
    val insertableColumns = allColumns.filter { !it.is_auto_increment && !it.has_default && !it.is_generated }
    if (insertableColumns.isEmpty()) return null

    val returningColumns = allColumns.filter { it.is_auto_increment || it.has_default || it.is_generated }

    val columnNames = insertableColumns.joinToString(", ") { it.name }
    val placeholders = insertableColumns.joinToString(", ") { "?" }

    val sql: String
    val command: String
    if (returningColumns.isNotEmpty()) {
      val returningNames = returningColumns.joinToString(", ") { it.name }
      sql = "INSERT INTO $qualifiedTable ($columnNames) VALUES ($placeholders) RETURNING $returningNames"
      command = ":one"
    } else {
      sql = "INSERT INTO $qualifiedTable ($columnNames) VALUES ($placeholders)"
      command = ":exec"
    }

    return ParsedQuery(
      name = "insert$methodSuffix",
      command = command,
      sql = sql,
      comments = emptyList(),
    )
  }

  private fun synthesizeFindById(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${it.name} = ?" }
    return ParsedQuery(
      name = "find${methodSuffix}ById",
      command = ":many",
      sql = "SELECT * FROM $qualifiedTable WHERE $whereClause",
      comments = emptyList(),
    )
  }

  private fun synthesizeExistsById(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${it.name} = ?" }
    return ParsedQuery(
      name = "exists${methodSuffix}ById",
      command = ":one",
      sql = "SELECT EXISTS(SELECT 1 FROM $qualifiedTable WHERE $whereClause)",
      comments = emptyList(),
    )
  }

  private fun synthesizeFindAll(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "findAll$methodSuffix",
    command = ":many",
    sql = "SELECT * FROM $qualifiedTable",
    comments = emptyList(),
  )

  private fun synthesizeCount(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "count$methodSuffix",
    command = ":one",
    sql = "SELECT COUNT(*) FROM $qualifiedTable",
    comments = emptyList(),
  )

  private fun synthesizeDeleteById(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${it.name} = ?" }
    return ParsedQuery(
      name = "delete${methodSuffix}ById",
      command = ":execrows",
      sql = "DELETE FROM $qualifiedTable WHERE $whereClause",
      comments = emptyList(),
    )
  }

  private fun synthesizeDeleteAll(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "deleteAll$methodSuffix",
    command = ":execrows",
    sql = "DELETE FROM $qualifiedTable",
    comments = emptyList(),
  )

  /**
   * Returns the schema-qualified table name if the schema is not `public`, otherwise just the table name.
   */
  private fun qualifiedTableName(table: Table): String {
    val schema = table.rel?.schema
    val name = table.rel?.name ?: error("qualifiedTableName called with null table name")
    return if (schema != null && schema != "public") "$schema.$name" else name
  }
}
