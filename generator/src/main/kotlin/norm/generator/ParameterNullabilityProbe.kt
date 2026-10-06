package norm.generator

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Asks PostgreSQL whether each `?` placeholder of a statement may be `null`.
 *
 * The statement is prepared to learn the type of each parameter. It is then compiled into a temporary SQL-standard
 * function (`BEGIN ATOMIC ... END`) that takes one argument of that type per placeholder. PostgreSQL parses the
 * body into a query tree, which [ParameterNullabilityClassifier] reads. Both objects are removed again before
 * [classify] returns.
 *
 * PostgreSQL limits a function to [MAX_FUNCTION_ARGUMENTS] arguments. A statement with more placeholders is
 * compiled once per chunk of that many placeholders. Each chunk replaces the other placeholders with
 * `CAST(NULL AS <their type>)`, and one `PREPARE` serves every chunk. A placeholder of a pseudo-type such as `record`
 * belongs to no chunk, because no SQL function takes such an argument.
 *
 * A function body rejects `SELECT ... INTO`. When PostgreSQL rejects a function, the probe tries the statement
 * without each of its `INTO` clauses and keeps the first that compiles.
 *
 * The same trees name the parameters that a function call takes as arguments, as [FunctionArgumentNamer] describes.
 *
 * The text is split at each top-level `;`, and each statement is probed on its own. A leading `EXPLAIN` is probed
 * through the statement it wraps.
 *
 * Callers prepare the statement first. A rejected `PREPARE` of a statement that starts with `SELECT`, `VALUES`,
 * `TABLE`, `WITH`, `INSERT`, `UPDATE`, `DELETE`, or `MERGE` therefore means the translation of the SQL is wrong,
 * and the probe throws. A statement that starts with any other word yields no classified parameters. So does a
 * statement whose function PostgreSQL rejects.
 *
 * Assumes [connection] is in autocommit mode, so a rejected statement does not abort a transaction.
 *
 * @param connection the analyzer's connection to a database with the schema applied.
 */
internal class ParameterNullabilityProbe(private val connection: Connection) {

  private val catalog = JdbcParameterCatalog(connection)
  private val classifier = ParameterNullabilityClassifier(catalog)
  private val reservedWords: Set<String> by lazy { readReservedWords(connection) }

  /**
   * Classifies the placeholders of [sql], one statement at a time. The JDBC driver splits [sql] at each top-level `;`
   * and numbers the placeholders of each part from one. The probe does the same, then merges the results back to the
   * numbering of the whole text. An `EXPLAIN` is probed through the statement it wraps, because `EXPLAIN ANALYZE`
   * runs that statement.
   *
   * @param queryName names the query in the error when [sql] cannot be translated.
   * @return the classified parameters keyed by 1-based parameter number; empty when [sql] has no placeholder,
   *   PostgreSQL rejects the function, or the statement is not one `PREPARE` accepts.
   * @throws IllegalStateException if PostgreSQL rejects the translated `PREPARE` of a preparable statement; the
   *   cause is the `SQLException`.
   * @throws IllegalArgumentException if the `prosqlbody` of a probe function is not a well-formed node tree.
   * @throws SQLException if the connection fails while reading the probe's results or cleaning up.
   */
  @Throws(SQLException::class)
  fun classify(sql: String, queryName: String = "query"): Map<Int, InferredParameter> {
    val parameters = mutableMapOf<Int, InferredParameter>()
    val functionArgumentNamer = FunctionArgumentNamer(catalog::function)
    var placeholdersBefore = 0
    for (text in splitStatements(sql)) {
      val classified = probeStatement(text.substring(explainedStatementStart(text)), queryName, functionArgumentNamer)
      for ((number, parameter) in classified) parameters[placeholdersBefore + number] = parameter
      placeholdersBefore += placeholderPositions(text).size
    }
    return parameters
  }

  /**
   * Probes one statement, whose placeholders are numbered from `$1`.
   *
   * @return the classified parameters, empty when [sql] has no placeholder, PostgreSQL rejects a function, or the
   *   statement is not one `PREPARE` accepts.
   */
  private fun probeStatement(
    sql: String,
    queryName: String,
    functionArgumentNamer: FunctionArgumentNamer,
  ): Map<Int, InferredParameter> {
    if (placeholderPositions(sql).isEmpty()) return emptyMap()
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val statementName = "norm_parameter_probe_$suffix"
    try {
      execute("PREPARE $statementName AS ${replaceParameterPlaceholders(sql) { "\$${it + 1}" }}")
    } catch (cause: SQLException) {
      if (!startsPreparableStatement(sql)) return emptyMap()
      throw IllegalStateException("Could not translate query '$queryName' for parameter analysis.", cause)
    }
    try {
      val types = readParameterTypes(statementName)
      // The `INTO` clause names the new table and holds no parameter.
      return classifyChunks(sql, types, functionArgumentNamer)
        ?: withoutEachIntoClause(sql, reservedWords).firstNotNullOfOrNull {
          classifyChunks(it, types, functionArgumentNamer)
        }
        ?: emptyMap()
    } finally {
      execute("DEALLOCATE $statementName")
    }
  }

  /**
   * Classifies the parameters of [sql] one chunk at a time. A parameter of a pseudo-type such as `record` cannot be a
   * function argument, so it is a typed `NULL` in every chunk and is not classified.
   *
   * @return the classified parameters, or `null` when PostgreSQL rejects a function.
   */
  private fun classifyChunks(
    sql: String,
    types: List<ParameterType>,
    functionArgumentNamer: FunctionArgumentNamer,
  ): Map<Int, InferredParameter>? {
    val parameters = mutableMapOf<Int, InferredParameter>()
    val nodeTrees = mutableListOf<FunctionArgumentNamer.ChunkTree>()
    for (chunk in types.indices.filterNot { types[it].isPseudo }.chunked(MAX_FUNCTION_ARGUMENTS)) {
      val nodeTree = readNodeTree(sql, types, chunk) ?: return null
      for ((chunkNumber, parameter) in classifier.classify(nodeTree)) {
        parameters[chunk[chunkNumber - 1] + 1] = parameter
      }
      nodeTrees += FunctionArgumentNamer.ChunkTree(chunk, nodeTree)
    }
    for ((number, name) in functionArgumentNamer.name(nodeTrees)) {
      parameters[number] = parameters[number]?.copy(name = name) ?: InferredParameter(name, nullability = null)
    }
    return parameters
  }

  /**
   * Compiles [sql] into a function of the parameters at [chunk] (0-based indexes into [types]), numbered
   * `$1` upward in the order of [chunk], and reads its body. The other placeholders become typed `NULL`s.
   *
   * @return the node tree, or `null` when PostgreSQL rejects the function.
   * @throws IllegalArgumentException if the function's `prosqlbody` is not a well-formed node tree.
   */
  private fun readNodeTree(sql: String, types: List<ParameterType>, chunk: List<Int>): PgNodeValue? {
    val numbers = chunk.withIndex().associate { (number, index) -> index to number + 1 }
    val body = replaceParameterPlaceholders(sql) { index ->
      numbers[index]?.let { "\$$it" } ?: "CAST(NULL AS ${types[index].name})"
    }
    val signature = chunk.joinToString(", ") { types[it].name }
    return withProsqlbodyNodeTree(connection, body, signature, "void", onRejectedCreate = { null }) { nodeTree ->
      parsePgNodeValue(nodeTree)
    }
  }

  /**
   * Reads the type of each parameter of the prepared statement from `pg_prepared_statements` as text that
   * `CREATE FUNCTION` accepts. `ParameterMetaData.getParameterTypeName` drops the schema of `public."Big"` and
   * names array types `_int4`.
   */
  private fun readParameterTypes(statementName: String): List<ParameterType> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        """
        SELECT parameter.type::text, type.typtype = 'p'
        FROM pg_prepared_statements statement
        CROSS JOIN LATERAL unnest(statement.parameter_types) WITH ORDINALITY AS parameter(type, number)
        JOIN pg_type type ON type.oid = parameter.type
        WHERE statement.name = '$statementName'
        ORDER BY parameter.number
        """.trimIndent(),
      ).use { resultSet ->
        val types =
          buildList { while (resultSet.next()) add(ParameterType(resultSet.getString(1), resultSet.getBoolean(2))) }
        check(types.isNotEmpty()) { "No pg_prepared_statements row found for probe statement $statementName" }
        types
      }
    }

  private fun execute(statementText: String) {
    connection.createStatement().use { it.execute(statementText) }
  }

  /**
   * Describes the type of a parameter.
   *
   * @property name the type as `CREATE FUNCTION` accepts it.
   * @property isPseudo whether the type is a pseudo-type, which no SQL function takes as an argument.
   */
  private class ParameterType(val name: String, val isPseudo: Boolean)

  companion object {
    /** Holds PostgreSQL's `FUNC_MAX_ARGS`, the most arguments a function takes. */
    const val MAX_FUNCTION_ARGUMENTS: Int = 100
  }
}
