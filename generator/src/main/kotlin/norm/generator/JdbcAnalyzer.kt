package norm.generator

import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import plugin.Schema
import plugin.Table
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSetMetaData

/**
 * Analyzes PostgreSQL schemas and queries using JDBC metadata APIs.
 *
 * Produces the same Wire protobuf types ([Catalog], [Query]) that the generator consumes,
 * replacing the previous sqlc-based pipeline with direct database introspection.
 *
 * Uses [DatabaseMetaData] for schema introspection and
 * [java.sql.PreparedStatement.getMetaData] / [java.sql.PreparedStatement.getParameterMetaData]
 * for query type analysis. This works because PostgreSQL's JDBC driver prepares statements
 * server-side and returns full type information without executing the query.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
public class JdbcAnalyzer(private val connection: Connection) {

  private val catalogLoader = PgCatalogLoader(connection)
  private val parameterInferrer = SqlParameterInferrer(catalogLoader.functionOverloads)

  /**
   * Builds a [Catalog] representing the database schema.
   *
   * Introspects tables, columns, enums, and primary keys using JDBC metadata
   * and PostgreSQL system catalog queries.
   *
   * @param schemas Schema names to introspect. Defaults to `"public"`.
   * @return A [Catalog] with the same structure the generator expects.
   */
  public fun buildCatalog(schemas: List<String> = listOf("public")): Catalog {
    val schemaObjects = schemas.map { schemaName ->
      val tables = introspectTables(schemaName)
      val enums = catalogLoader.introspectEnums(schemaName)
      val domains = catalogLoader.introspectDomains(schemaName)
      Schema(
        name = schemaName,
        tables = tables,
        enums = enums,
        domains = domains,
      )
    }

    return Catalog(
      default_schema = schemas.first(),
      schemas = schemaObjects,
    )
  }

  /**
   * Analyzes a parsed query to determine its parameter types and result column types.
   *
   * Prepares the statement using the SQL's `?` placeholders and reads metadata from the
   * prepared statement without executing it.
   *
   * @param parsedQuery The query parsed from a SQL file.
   * @param catalog The schema catalog, used to attach table references to result columns.
   * @return A [Query] proto object with full type information.
   */
  public fun analyzeQuery(parsedQuery: ParsedQuery, catalog: Catalog): Query {
    val jdbcSql = parsedQuery.sql
    val isCallStatement = parsedQuery.sql.trimStart().startsWith("CALL ", ignoreCase = true)

    val resultColumns: List<Column>
    val parameters: List<Parameter>

    val inferredParameters = parameterInferrer.inferParameterInfo(parsedQuery.sql)
    // Named parameters from the query file take priority over inferred names
    val inferredNames = inferredParameters.mapValues { it.value.name } + parsedQuery.namedParameters
    val notNullByParameter = parameterInferrer.resolveParameterNotNull(inferredParameters, catalog)

    if (isCallStatement) {
      // CALL statements don't return result sets and may not support getMetaData()
      resultColumns = emptyList()
      parameters = analyzeCallParameters(parsedQuery.sql, jdbcSql)
    } else {
      connection.prepareStatement(jdbcSql).use { ps ->
        resultColumns = buildResultColumns(ps.metaData, catalog, parsedQuery.sql)
        parameters =
          buildParameters(ps.parameterMetaData, inferredNames, notNullByParameter, inferredParameters, catalog)
      }
    }

    return Query(
      text = parsedQuery.sql,
      name = parsedQuery.name,
      cmd = parsedQuery.command,
      columns = resultColumns,
      params = parameters,
      comments = parsedQuery.comments,
      is_synthesized_insert = parsedQuery.isSynthesizedInsert,
    )
  }

  private fun introspectTables(schemaName: String): List<Table> {
    val dbMeta = connection.metaData
    val tableComments = catalogLoader.loadTableComments(schemaName)
    val columnComments = catalogLoader.loadColumnComments(schemaName)

    // Discover all table-like relations. PostgreSQL's JDBC driver reports different relkinds as separate types:
    //   "TABLE"              — regular tables (relkind='r') and partition children
    //   "PARTITIONED TABLE"  — partitioned parents (relkind='p')
    //   "VIEW"               — views (relkind='v')
    //   "MATERIALIZED VIEW"  — materialized views (relkind='m')
    // Partition children (e.g., event_2026) are excluded — they are implementation details of the parent.
    val partitionChildren = catalogLoader.loadPartitionChildren(schemaName)
    val tableEntries = mutableListOf<Pair<String, String>>() // (name, TABLE_TYPE)
    val tableTypes = arrayOf("TABLE", "PARTITIONED TABLE", "VIEW", "MATERIALIZED VIEW")
    dbMeta.getTables(null, schemaName, null, tableTypes).use { rs ->
      while (rs.next()) {
        val name = rs.getString("TABLE_NAME")
        val type = rs.getString("TABLE_TYPE")
        if (name !in partitionChildren) {
          tableEntries.add(name to type)
        }
      }
    }

    // For views and materialized views, JDBC's getColumns() reports all columns as nullable because
    // views carry no constraints. Resolve actual nullability by tracing columns back to their source
    // base table columns via pg_depend, where NOT NULL constraints exist.
    val viewNotNullColumns = catalogLoader.loadViewColumnNullability(schemaName)
    // For regular views (not materialized views), columns on the nullable side of an outer join inside
    // the view's definition must remain nullable even when the base table column is NOT NULL. Remove
    // such columns from the NOT NULL set to prevent false non-null claims.
    val viewOuterJoinNullableColumns = catalogLoader.loadViewOuterJoinNullableColumns(schemaName)
    val correctedViewNotNullColumns = viewNotNullColumns - viewOuterJoinNullableColumns

    return tableEntries.map { (tableName, tableType) ->
      val primaryKeyColumns = loadPrimaryKeyColumns(dbMeta, schemaName, tableName)
      val columns =
        loadColumns(dbMeta, schemaName, tableName, columnComments, correctedViewNotNullColumns, primaryKeyColumns)
      Table(
        rel = Identifier(name = tableName, schema = schemaName),
        columns = columns,
        comment = tableComments[tableName].orEmpty(),
        is_view = tableType == "VIEW" || tableType == "MATERIALIZED VIEW",
      )
    }
  }

  private fun loadPrimaryKeyColumns(dbMeta: DatabaseMetaData, schemaName: String, tableName: String): Set<String> =
    buildSet {
      dbMeta.getPrimaryKeys(null, schemaName, tableName).use { rs ->
        while (rs.next()) {
          add(rs.getString("COLUMN_NAME"))
        }
      }
    }

  private fun loadColumns(
    dbMeta: DatabaseMetaData,
    schemaName: String,
    tableName: String,
    columnComments: Map<String, String>,
    viewNotNullColumns: Set<String>,
    primaryKeyColumns: Set<String>,
  ): List<Column> = buildList {
    dbMeta.getColumns(null, schemaName, tableName, null).use { rs ->
      while (rs.next()) {
        val columnName = rs.getString("COLUMN_NAME")
        val typeName = rs.getString("TYPE_NAME")
        val nullable = rs.getString("IS_NULLABLE")
        val comment = columnComments["$tableName.$columnName"]
        val isAutoIncrement = rs.getString("IS_AUTOINCREMENT") == "YES"
        val hasDefault = !rs.getString("COLUMN_DEF").isNullOrEmpty()
        val isGenerated = rs.getString("IS_GENERATEDCOLUMN") == "YES"

        val (baseName, isArray) = resolveTypeName(typeName)

        // For view/matview columns, override JDBC's nullable with the resolved value from pg_depend.
        val notNull = nullable == "NO" || "$tableName.$columnName" in viewNotNullColumns

        add(
          Column(
            name = columnName,
            not_null = notNull,
            is_array = isArray,
            array_dims = if (isArray) 1 else 0,
            comment = comment.orEmpty(),
            type = Identifier(name = baseName),
            table = Identifier(name = tableName, schema = schemaName),
            original_name = columnName,
            is_primary_key = columnName in primaryKeyColumns,
            is_auto_increment = isAutoIncrement,
            has_default = hasDefault,
            is_generated = isGenerated,
          ),
        )
      }
    }
  }

  private fun buildResultColumns(rsmd: ResultSetMetaData?, catalog: Catalog, sql: String): List<Column> {
    if (rsmd == null) return emptyList()

    val columnNullability = catalogLoader.queryColumnNullability(sql)
    val selectItems = parseSelectItems(sql)

    val columns = mutableListOf<Column>()
    for (i in 1..rsmd.columnCount) {
      val (baseName, isArray) = resolveTypeName(rsmd.getColumnTypeName(i))
      val tableName = rsmd.getTableName(i).ifEmpty { null }
      val columnLabel = rsmd.getColumnLabel(i)
      val selectItem = selectItems.getOrNull(i - 1)

      // PostgreSQL JDBC returns the alias for both getColumnName and getColumnLabel when AS is used.
      // Use the parsed SELECT item to resolve the original column name for comment lookup.
      val originalColumnName = selectItem?.columnName ?: rsmd.getColumnName(i)

      // Look up the catalog column for comment resolution.
      val catalogColumn = tableName?.let { catalog.findColumn(it, originalColumnName) }

      // The node tree is the authoritative source for nullability. columnNullability[i-1] is true
      // when nullable. Default to true (nullable) when the index is out of bounds.
      val notNull = !(columnNullability.getOrElse(i - 1) { true })

      val comment = catalogColumn?.comment.orEmpty()

      columns.add(
        Column(
          name = columnLabel,
          not_null = notNull,
          is_array = isArray,
          array_dims = if (isArray) 1 else 0,
          comment = comment,
          type = Identifier(name = baseName),
          table = tableName?.let { resolveTableIdentifier(it, catalog) },
          original_name = originalColumnName,
        ),
      )
    }
    return columns
  }

  /**
   * Builds [Parameter] objects from JDBC [ParameterMetaData][java.sql.ParameterMetaData].
   *
   * JDBC's [java.sql.ParameterMetaData.getParameterTypeName] returns the base Postgres type name for
   * domain columns (e.g., `"text"` instead of `"email"`). When [inferredParameters] maps a parameter
   * to a catalog column, the catalog column's type name is used instead to preserve domain information.
   *
   * @param pmd The parameter metadata from a prepared statement.
   * @param inferredNames Map from 1-based parameter number to inferred column name.
   * @param notNullByParameter Map from 1-based parameter number to whether the parameter is non-nullable.
   *   Parameters not in this map default to non-nullable.
   * @param inferredParameters Map from 1-based parameter number to inferred parameter info, used to look
   *   up column types and comments from the catalog.
   * @param catalog The schema catalog, used to look up column types and comments for parameters.
   */
  private fun buildParameters(
    pmd: java.sql.ParameterMetaData,
    inferredNames: Map<Int, String> = emptyMap(),
    notNullByParameter: Map<Int, Boolean> = emptyMap(),
    inferredParameters: Map<Int, InferredParameter> = emptyMap(),
    catalog: Catalog? = null,
  ): List<Parameter> {
    val parameters = mutableListOf<Parameter>()
    for (i in 1..pmd.parameterCount) {
      val (jdbcTypeName, isArray) = resolveTypeName(pmd.getParameterTypeName(i))

      val inferred = inferredParameters[i]
      val tableName = inferred?.tableName
      val columnName = inferred?.columnName ?: inferred?.name

      // Look up the catalog column once for type name and comment resolution.
      val catalogColumn = if (catalog != null && tableName != null && columnName != null) {
        catalog.findColumn(tableName, columnName)
      } else {
        null
      }

      // JDBC's getParameterTypeName() returns the base Postgres type for domain columns (e.g., "text"
      // instead of "email"), losing domain information. When the inferred parameter maps to a known
      // catalog column, use the catalog column's type name instead — it was populated via
      // DatabaseMetaData.getColumns() which preserves the domain name (e.g., "email").
      val typeName = catalogColumn?.type?.name ?: jdbcTypeName

      // When a function wraps the parameter (e.g., crypt(?, gen_salt('bf'))), the column comment describes
      // the stored value, not the caller's input. Skip the comment in that case.
      val isInsideFunctionCall = inferred?.columnName != null && inferred.columnName != inferred.name
      val comment = if (!isInsideFunctionCall && catalogColumn != null) {
        catalogColumn.comment
      } else {
        ""
      }

      parameters.add(
        Parameter(
          number = i,
          column = Column(
            name = inferredNames[i] ?: "p$i",
            not_null = notNullByParameter[i] ?: true,
            is_array = isArray,
            array_dims = if (isArray) 1 else 0,
            comment = comment,
            type = Identifier(name = typeName),
            table = if (catalog != null && tableName != null) resolveTableIdentifier(tableName, catalog) else null,
            original_name = columnName.orEmpty(),
          ),
        ),
      )
    }
    return parameters
  }

  /**
   * Analyzes parameters for CALL statements by querying the pg_catalog for procedure argument types.
   *
   * Prefers `pg_proc` lookup for argument names since JDBC `ParameterMetaData` only provides types.
   * Falls back to preparing the statement directly if the procedure isn't found in `pg_proc`.
   */
  private fun analyzeCallParameters(originalSql: String, jdbcSql: String): List<Parameter> {
    val procName = CALL_PROCEDURE_NAME.find(originalSql)?.groupValues?.get(1)
    if (procName != null) {
      val params = catalogLoader.lookupProcedureParameters(procName)
      if (params.isNotEmpty()) return params
    }

    return try {
      connection.prepareStatement(jdbcSql).use { ps ->
        buildParameters(ps.parameterMetaData)
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Resolves a PostgreSQL type name, stripping the array prefix.
   *
   * Array types in Postgres are prefixed with `_` (e.g., `_int4` for `int4[]`).
   * Domain type names are preserved (not collapsed to their base type) so that the generator's
   * [TypeRepository] can resolve them to value classes.
   *
   * @return A pair of (type name, isArray).
   */
  private fun resolveTypeName(typeName: String): Pair<String, Boolean> {
    val isArray = typeName.startsWith("_")
    val rawBase = if (isArray) typeName.removePrefix("_") else typeName
    return rawBase to isArray
  }

  /**
   * Resolves a table name to an [Identifier] by finding it in the catalog.
   * Falls back to a simple identifier if the table isn't found.
   */
  private fun resolveTableIdentifier(tableName: String, catalog: Catalog): Identifier =
    catalog.findTable(tableName)?.rel ?: Identifier(name = tableName)

  /**
   * Queries PostgreSQL for reserved keywords that cannot be used as unquoted identifiers.
   *
   * Uses `pg_get_keywords()` and selects categories `R` (reserved) and `T` (reserved, can be
   * function or type name). These are the words that PostgreSQL rejects as bare identifiers in
   * column/table positions.
   *
   * @return A set of lowercase reserved words.
   */
  public fun fetchReservedWords(): Set<String> = buildSet {
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT word FROM pg_get_keywords() WHERE catcode IN ('R', 'T')").use { resultSet ->
        while (resultSet.next()) add(resultSet.getString(1))
      }
    }
  }

  /**
   * Builds an identifier-quoting function based on the connected PostgreSQL instance's reserved words.
   *
   * The returned function wraps an identifier in double-quotes only when necessary:
   * - The identifier is a PostgreSQL reserved word (per [fetchReservedWords]).
   * - The identifier contains characters that require quoting (uppercase letters, leading digits,
   *   special characters other than `_` and `$`).
   *
   * Normal identifiers like `author`, `id`, `name` pass through unquoted.
   */
  public fun buildIdentifierQuoter(): (String) -> String {
    val reservedWords = fetchReservedWords()
    return { identifier ->
      if (needsQuoting(identifier, reservedWords)) "\"$identifier\"" else identifier
    }
  }

  private companion object {
    private val CALL_PROCEDURE_NAME = Regex("""CALL\s+(\w+)\s*\(""", RegexOption.IGNORE_CASE)
    private val SAFE_IDENTIFIER = Regex("[a-z_][a-z0-9_\$]*")

    private fun needsQuoting(identifier: String, reservedWords: Set<String>): Boolean =
      identifier.lowercase() in reservedWords || !identifier.matches(SAFE_IDENTIFIER)
  }
}
