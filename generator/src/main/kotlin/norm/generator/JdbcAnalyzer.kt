package norm.generator

import plugin.Catalog
import plugin.Column
import plugin.Identifier
import plugin.Parameter
import plugin.Query
import plugin.Schema
import plugin.Table
import java.sql.Connection
import java.sql.ResultSetMetaData

/**
 * Analyzes PostgreSQL schemas and queries using JDBC metadata APIs.
 *
 * Produces the same Wire protobuf types ([Catalog], [Query]) that the generator consumes,
 * replacing the previous sqlc-based pipeline with direct database introspection.
 *
 * Uses [java.sql.DatabaseMetaData] for schema introspection and
 * [java.sql.PreparedStatement.getMetaData] / [java.sql.PreparedStatement.getParameterMetaData]
 * for query type analysis. This works because PostgreSQL's JDBC driver prepares statements
 * server-side and returns full type information without executing the query.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
public class JdbcAnalyzer(private val connection: Connection) {

  private val catalogLoader = PgCatalogLoader(connection)
  private val nullabilityAnalyzer = SqlNullabilityAnalyzer(catalogLoader.functionOverloads)
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
      Schema(
        name = schemaName,
        tables = tables,
        enums = enums,
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
        resultColumns = buildResultColumns(ps.metaData, catalog, parsedQuery.sql, notNullByParameter)
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
    )
  }

  private fun introspectTables(schemaName: String): List<Table> {
    val dbMeta = connection.metaData
    val tables = mutableListOf<Table>()
    val primaryKeys = mutableMapOf<String, Set<String>>()
    val tableComments = catalogLoader.loadTableComments(schemaName)
    val columnComments = catalogLoader.loadColumnComments(schemaName)

    // Discover tables
    val tableNames = mutableListOf<String>()
    dbMeta.getTables(null, schemaName, null, arrayOf("TABLE")).use { rs ->
      while (rs.next()) {
        tableNames.add(rs.getString("TABLE_NAME"))
      }
    }

    // Load primary keys for each table
    for (tableName in tableNames) {
      val pks = mutableSetOf<String>()
      dbMeta.getPrimaryKeys(null, schemaName, tableName).use { rs ->
        while (rs.next()) {
          pks.add(rs.getString("COLUMN_NAME"))
        }
      }
      primaryKeys[tableName] = pks
    }

    // Load columns for each table
    for (tableName in tableNames) {
      val columns = mutableListOf<Column>()
      dbMeta.getColumns(null, schemaName, tableName, null).use { rs ->
        while (rs.next()) {
          val columnName = rs.getString("COLUMN_NAME")
          val typeName = rs.getString("TYPE_NAME")
          val nullable = rs.getString("IS_NULLABLE")
          val comment = columnComments["$tableName.$columnName"]

          val (baseName, isArray) = resolveTypeName(typeName)

          columns.add(
            Column(
              name = columnName,
              not_null = nullable == "NO",
              is_array = isArray,
              array_dims = if (isArray) 1 else 0,
              comment = comment.orEmpty(),
              type = Identifier(name = baseName),
              table = Identifier(name = tableName, schema = schemaName),
              original_name = columnName,
              is_primary_key = columnName in primaryKeys.getValue(tableName),
            ),
          )
        }
      }

      tables.add(
        Table(
          rel = Identifier(name = tableName, schema = schemaName),
          columns = columns,
          comment = tableComments[tableName].orEmpty(),
        ),
      )
    }

    return tables
  }

  private fun buildResultColumns(
    rsmd: ResultSetMetaData?,
    catalog: Catalog,
    sql: String,
    notNullByParameter: Map<Int, Boolean> = emptyMap(),
  ): List<Column> {
    if (rsmd == null) return emptyList()

    val nonNullAliases = nullabilityAnalyzer.findNonNullAliases(sql, notNullByParameter)
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

      val notNull = when (rsmd.isNullable(i)) {
        ResultSetMetaData.columnNoNulls -> true
        ResultSetMetaData.columnNullable -> false
        // columnNullableUnknown: the driver can't determine nullability from schema metadata.
        // This happens for computed expressions (EXISTS, COUNT, COALESCE, function calls, crosstab
        // columns, etc.). Default to nullable (safer), but override to non-null for expressions
        // that are known to never produce null.
        else -> columnLabel in nonNullAliases
      }

      val comment = tableName?.let { catalog.findColumn(it, originalColumnName)?.comment }.orEmpty()

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
   * @param pmd The parameter metadata from a prepared statement.
   * @param inferredNames Map from 1-based parameter number to inferred column name.
   * @param notNullByParameter Map from 1-based parameter number to whether the parameter is non-nullable.
   *   Parameters not in this map default to non-nullable.
   * @param inferredParameters Map from 1-based parameter number to inferred parameter info, used to look
   *   up column comments from the catalog.
   * @param catalog The schema catalog, used to look up column comments for parameters.
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
      val (baseName, isArray) = resolveTypeName(pmd.getParameterTypeName(i))

      val inferred = inferredParameters[i]
      val tableName = inferred?.tableName
      val columnName = inferred?.columnName ?: inferred?.name
      val comment = if (catalog != null && tableName != null && columnName != null) {
        catalog.findColumn(tableName, columnName)?.comment.orEmpty()
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
            type = Identifier(name = baseName),
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
   * Resolves a PostgreSQL type name, stripping the array prefix and resolving domain types.
   *
   * Array types in Postgres are prefixed with `_` (e.g., `_int4` for `int4[]`).
   * Domain types are resolved to their base type via [PgCatalogLoader.domainTypes].
   *
   * @return A pair of (base type name, isArray).
   */
  private fun resolveTypeName(typeName: String): Pair<String, Boolean> {
    val isArray = typeName.startsWith("_")
    val rawBase = if (isArray) typeName.removePrefix("_") else typeName
    val baseName = catalogLoader.domainTypes[rawBase] ?: rawBase
    return baseName to isArray
  }

  /**
   * Resolves a table name to an [Identifier] by finding it in the catalog.
   * Falls back to a simple identifier if the table isn't found.
   */
  private fun resolveTableIdentifier(tableName: String, catalog: Catalog): Identifier =
    catalog.findTable(tableName)?.rel ?: Identifier(name = tableName)

  private companion object {
    private val CALL_PROCEDURE_NAME = Regex("""CALL\s+(\w+)\s*\(""", RegexOption.IGNORE_CASE)
  }
}
