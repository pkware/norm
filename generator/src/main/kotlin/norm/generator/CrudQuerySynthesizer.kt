package norm.generator

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
   * @param quoteIdentifier A function that wraps SQL identifiers in double-quotes when they need
   *   quoting (e.g., reserved words). Defaults to identity (no quoting), which is suitable for
   *   unit tests that don't have a live database connection.
   * @return The combined list with [userQueries] first, followed by non-conflicting CRUD queries.
   */
  public fun synthesizeAndMerge(
    catalog: Catalog,
    userQueries: List<ParsedQuery>,
    quoteIdentifier: (String) -> String = { it },
  ): List<ParsedQuery> {
    val crudQueries = synthesize(catalog, quoteIdentifier)
    val userQueryNames = userQueries.map { it.name }.toSet()
    return userQueries + crudQueries.filter { it.name !in userQueryNames }
  }

  /**
   * Synthesizes CRUD queries for all non-view tables across all schemas.
   *
   * @param quoteIdentifier A function that wraps SQL identifiers in double-quotes when they need
   *   quoting (e.g., reserved words). Defaults to identity (no quoting).
   * @return A list of [ParsedQuery] objects ready for JDBC analysis.
   */
  public fun synthesize(catalog: Catalog, quoteIdentifier: (String) -> String = { it }): List<ParsedQuery> = buildList {
    for (schema in catalog.schemas) {
      for (table in schema.tables) {
        if (table.isView) continue
        addAll(synthesizeForTable(table, quoteIdentifier))
      }
    }
  }

  private fun synthesizeForTable(table: Table, quoteIdentifier: (String) -> String): List<ParsedQuery> {
    val tableName = table.rel.name
    val methodSuffix = tableName.snakeToCamelCase().titleCase()
    val qualifiedTable = qualifiedTableName(table, quoteIdentifier)
    val primaryKeyColumns = table.columns.filter(Column::isPrimaryKey)
    val allColumns = table.columns
    val sourceFile = "<synthesized CRUD for table '$qualifiedTable'>"

    val queries = buildList {
      // INSERT — null when all columns are auto-increment and/or generated-always
      synthesizeInsert(qualifiedTable, methodSuffix, allColumns, quoteIdentifier)?.let(::add)

      // PK-dependent methods
      if (primaryKeyColumns.isNotEmpty()) {
        add(synthesizeFind(qualifiedTable, methodSuffix, primaryKeyColumns, quoteIdentifier))
        add(synthesizeExists(qualifiedTable, methodSuffix, primaryKeyColumns, quoteIdentifier))
        add(synthesizeDelete(qualifiedTable, methodSuffix, primaryKeyColumns, quoteIdentifier))
      }

      // PK-independent methods
      add(synthesizeFindAll(qualifiedTable, methodSuffix))
      add(synthesizeCount(qualifiedTable, methodSuffix))
      add(synthesizeDeleteAll(qualifiedTable, methodSuffix))
    }

    return queries.map { it.copy(sourceFile = sourceFile) }
  }

  /**
   * Generates an INSERT query. Auto-increment and generated-always columns are always excluded from
   * the VALUES clause and included in a RETURNING clause.
   *
   * Columns with a server-side `DEFAULT` are *overridable-default* columns (see
   * [ParsedQuery.overridableDefaultParameterPositions]): rather than being excluded like an
   * auto-increment or generated-always column, each becomes its own optional parameter on the
   * synthesized `insert*` function ([norm.generator.InterfaceBuilder]), placed in the VALUES clause
   * alongside the required columns so JDBC can type it ([JdbcAnalyzer.buildParameters]). They are
   * also kept in RETURNING — unconditionally, whether the caller overrides the column or not — so
   * the result row type stays stable across both cases.
   *
   * The VALUES clause here holds a `?` for every required AND every overridable-default column;
   * this SQL is only used for [JdbcAnalyzer] parameter-type analysis. At runtime,
   * [norm.generator.ImplementationBuilder] builds a different SQL string per call, substituting the
   * literal `DEFAULT` for any overridable-default column's `?` that the caller didn't supply a value
   * for.
   *
   * Parameter (and VALUES-clause column) order is required columns in table order, followed by
   * overridable-default columns in table order — see [ParsedQuery.overridableDefaultParameterPositions].
   *
   * `null` is returned only when the table has neither an insertable (required) column nor an
   * overridable-default column — i.e., every column is auto-increment and/or generated-always.
   */
  private fun synthesizeInsert(
    qualifiedTable: String,
    methodSuffix: String,
    allColumns: List<Column>,
    quoteIdentifier: (String) -> String,
  ): ParsedQuery? {
    val requiredColumns = allColumns.filter { !it.isAutoIncrement && !it.hasDefault && !it.isGenerated }
    val overridableDefaultColumns = allColumns.filter { it.hasDefault && !it.isAutoIncrement && !it.isGenerated }
    if (requiredColumns.isEmpty() && overridableDefaultColumns.isEmpty()) return null

    val insertColumns = requiredColumns + overridableDefaultColumns
    val returningColumns = allColumns.filter { it.isAutoIncrement || it.hasDefault || it.isGenerated }

    val columnNames = insertColumns.joinToString(", ") { quoteIdentifier(it.name) }
    val placeholders = insertColumns.joinToString(", ") { "?" }

    val sql: String
    val command: Command
    if (returningColumns.isNotEmpty()) {
      val returningNames = returningColumns.joinToString(", ") { quoteIdentifier(it.name) }
      sql = "INSERT INTO $qualifiedTable ($columnNames) VALUES ($placeholders) RETURNING $returningNames"
      command = Command.ONE
    } else {
      sql = "INSERT INTO $qualifiedTable ($columnNames) VALUES ($placeholders)"
      command = Command.EXEC
    }

    // 1-based positions of the overridable-default columns' `?` placeholders -- they always come
    // after the required columns' placeholders, per the VALUES-clause ordering above. Empty when
    // overridableDefaultColumns is empty, since a start > end IntRange is empty.
    val overridableDefaultPositions = (requiredColumns.size + 1..insertColumns.size).toSet()

    return ParsedQuery(
      name = "insert$methodSuffix",
      command = command,
      sql = sql,
      comments = emptyList(),
      isSynthesizedInsert = true,
      overridableDefaultParameterPositions = overridableDefaultPositions,
    )
  }

  private fun synthesizeFind(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
    quoteIdentifier: (String) -> String,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${quoteIdentifier(it.name)} = ?" }
    return ParsedQuery(
      name = "find${methodSuffix}${primaryKeySuffix(primaryKeyColumns)}",
      command = Command.MANY,
      sql = "SELECT * FROM $qualifiedTable WHERE $whereClause",
      comments = emptyList(),
    )
  }

  private fun synthesizeExists(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
    quoteIdentifier: (String) -> String,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${quoteIdentifier(it.name)} = ?" }
    return ParsedQuery(
      name = "exists${methodSuffix}${primaryKeySuffix(primaryKeyColumns)}",
      command = Command.ONE,
      sql = "SELECT EXISTS(SELECT 1 FROM $qualifiedTable WHERE $whereClause)",
      comments = emptyList(),
    )
  }

  private fun synthesizeFindAll(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "findAll$methodSuffix",
    command = Command.MANY,
    sql = "SELECT * FROM $qualifiedTable",
    comments = emptyList(),
  )

  private fun synthesizeCount(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "count$methodSuffix",
    command = Command.ONE,
    sql = "SELECT COUNT(*) FROM $qualifiedTable",
    comments = emptyList(),
  )

  private fun synthesizeDelete(
    qualifiedTable: String,
    methodSuffix: String,
    primaryKeyColumns: List<Column>,
    quoteIdentifier: (String) -> String,
  ): ParsedQuery {
    val whereClause = primaryKeyColumns.joinToString(" AND ") { "${quoteIdentifier(it.name)} = ?" }
    return ParsedQuery(
      name = "delete${methodSuffix}${primaryKeySuffix(primaryKeyColumns)}",
      command = Command.EXEC_ROWS,
      sql = "DELETE FROM $qualifiedTable WHERE $whereClause",
      comments = emptyList(),
    )
  }

  private fun synthesizeDeleteAll(qualifiedTable: String, methodSuffix: String): ParsedQuery = ParsedQuery(
    name = "deleteAll$methodSuffix",
    command = Command.EXEC_ROWS,
    sql = "DELETE FROM $qualifiedTable",
    comments = emptyList(),
  )

  /**
   * Returns the schema-qualified table name if the schema is not `public`, otherwise just the table name.
   * Each part (schema, name) is independently passed through [quoteIdentifier].
   */
  private fun qualifiedTableName(table: Table, quoteIdentifier: (String) -> String): String {
    val schema = table.rel.schema
    val name = table.rel.name
    return if (schema.isNotEmpty() && schema != "public") {
      "${quoteIdentifier(schema)}.${quoteIdentifier(name)}"
    } else {
      quoteIdentifier(name)
    }
  }

  /**
   * Builds a `By` suffix from the primary key column names.
   *
   * Single column `id` produces `ById`. Composite key `(order_id, item_id)` produces
   * `ByOrderIdAndItemId`. Each column name is converted from snake_case to TitleCase.
   */
  private fun primaryKeySuffix(primaryKeyColumns: List<Column>): String =
    "By" + primaryKeyColumns.joinToString("And") { it.name.snakeToCamelCase().titleCase() }
}
