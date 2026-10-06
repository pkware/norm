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
 * compiled once per chunk of that many consecutive placeholders. Each chunk replaces the other placeholders with
 * `CAST(NULL AS <their type>)`, and one `PREPARE` serves every chunk.
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

  private val isNotNullDomain = memoized(::readNotNullDomain)
  private val typeChain = memoized(::readTypeChain)
  private val attribute = memoized(::readAttribute)
  private val relationName = memoized(::readRelationName)
  private val likeEscape = memoized(::readLikeEscape)

  private val classifier = ParameterNullabilityClassifier(
    isNotNullDomain = isNotNullDomain,
    columnTypeOid = { relid, attno -> attribute(relid to attno)?.typeOid },
    typeChain = typeChain,
    attributeName = { relid, attno -> attribute(relid to attno)?.name },
    relation = relationName,
    isLikeEscape = likeEscape,
  )

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
   * @throws SQLException if the connection fails while reading the probe's results or cleaning up.
   */
  @Throws(SQLException::class)
  fun classify(sql: String, queryName: String = "query"): Map<Int, InferredParameter> {
    val parameters = mutableMapOf<Int, InferredParameter>()
    var placeholdersBefore = 0
    for (text in splitStatements(sql)) {
      val classified = probeStatement(text.substring(explainedStatementStart(text)), queryName)
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
  private fun probeStatement(sql: String, queryName: String): Map<Int, InferredParameter> {
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
      val parameters = mutableMapOf<Int, InferredParameter>()
      for (chunk in types.indices.chunked(MAX_FUNCTION_ARGUMENTS)) {
        val nodeTree = readNodeTree(sql, types, chunk) ?: return emptyMap()
        for ((chunkNumber, parameter) in classifier.classify(nodeTree)) {
          parameters[chunk.first() + chunkNumber] = parameter
        }
      }
      return parameters
    } finally {
      execute("DEALLOCATE $statementName")
    }
  }

  /**
   * Compiles [sql] into a function of the parameters at [chunk] (0-based indexes into [types]), numbered
   * `$1` upward, and reads its body. The other placeholders become typed `NULL`s.
   *
   * @return the node tree, or `null` when PostgreSQL rejects the function.
   */
  private fun readNodeTree(sql: String, types: List<String>, chunk: List<Int>): String? {
    val body = replaceParameterPlaceholders(sql) { index ->
      if (index in chunk) "\$${index - chunk.first() + 1}" else "CAST(NULL AS ${types[index]})"
    }
    val signature = chunk.joinToString(", ") { types[it] }
    return withProsqlbodyNodeTree(connection, body, signature, "void", onRejectedCreate = { null }) { it }
  }

  /** Reads the name and type of the attribute with the given relation OID and number, `null` when it has none. */
  private fun readAttribute(key: Pair<Int, Int>): Attribute? = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT attname, atttypid::integer FROM pg_attribute " +
        "WHERE attrelid = ${key.first} AND attnum = ${key.second} AND NOT attisdropped",
    ).use { resultSet -> if (resultSet.next()) Attribute(resultSet.getString(1), resultSet.getInt(2)) else null }
  }

  private fun readRelationName(relid: Int): RelationName? = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = $relid",
    ).use { resultSet ->
      if (resultSet.next()) RelationName(resultSet.getString(1), resultSet.getString(2)) else null
    }
  }

  /** Tells whether [functionOid] is `pg_catalog.like_escape`, whatever its argument types. */
  private fun readLikeEscape(functionOid: Int): Boolean = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT proname = 'like_escape' AND pronamespace = 'pg_catalog'::regnamespace FROM pg_proc " +
        "WHERE oid = $functionOid",
    ).use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
  }

  /** Reads [typeOid] and every type in its `typbasetype` chain. */
  private fun readTypeChain(typeOid: Int): Set<Int> = connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      WITH RECURSIVE chain(oid, base) AS (
        SELECT oid, typbasetype FROM pg_type WHERE oid = $typeOid
        UNION ALL
        SELECT t.oid, t.typbasetype FROM pg_type t JOIN chain c ON t.oid = c.base
      )
      SELECT oid::integer FROM chain
      """.trimIndent(),
    ).use { resultSet -> buildSet { while (resultSet.next()) add(resultSet.getInt(1)) } }
  }

  /**
   * Tells whether [typeOid] is a domain declared `NOT NULL`, or a domain over one. The flag is on the domain that
   * declares it, so a domain over a `NOT NULL` domain has `typnotnull = false` itself.
   */
  private fun readNotNullDomain(typeOid: Int): Boolean = connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      WITH RECURSIVE chain(base, not_null) AS (
        SELECT typbasetype, typnotnull FROM pg_type WHERE oid = $typeOid AND typtype = 'd'
        UNION ALL
        SELECT t.typbasetype, t.typnotnull FROM pg_type t JOIN chain c ON t.oid = c.base AND t.typtype = 'd'
      )
      SELECT coalesce(bool_or(not_null), false) FROM chain
      """.trimIndent(),
    ).use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
  }

  /**
   * Reads the type of each parameter of the prepared statement from `pg_prepared_statements` as text that
   * `CREATE FUNCTION` accepts. `ParameterMetaData.getParameterTypeName` drops the schema of `public."Big"` and
   * names array types `_int4`.
   */
  private fun readParameterTypes(statementName: String): List<String> = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT parameter_types::text[] FROM pg_prepared_statements WHERE name = '$statementName'",
    ).use { resultSet ->
      check(resultSet.next()) { "No pg_prepared_statements row found for probe statement $statementName" }
      @Suppress("UNCHECKED_CAST")
      (resultSet.getArray(1).array as Array<String>).toList()
    }
  }

  private fun execute(statementText: String) {
    connection.createStatement().use { it.execute(statementText) }
  }

  private data class Attribute(val name: String, val typeOid: Int)

  private fun <K, V> memoized(read: (K) -> V): (K) -> V {
    val values = mutableMapOf<K, V>()
    return { key -> if (values.containsKey(key)) values.getValue(key) else read(key).also { values[key] = it } }
  }

  companion object {
    /** Holds PostgreSQL's `FUNC_MAX_ARGS`, the most arguments a function takes. */
    const val MAX_FUNCTION_ARGUMENTS: Int = 100
  }
}
