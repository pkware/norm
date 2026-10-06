package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Checks [ParameterNullabilityProbe]'s classification of the `?` placeholders in the template corpus against a
 * real PostgreSQL instance.
 *
 * A placeholder written to a column by `SET` has the column's nullability. PostgreSQL gives `null` a meaning in a
 * `coalesce` fallback or a `NULL` test, and it also stores `null` in an array or composite element. Placeholders in
 * those places are nullable. A placeholder in a join, `WHERE`, `HAVING`, or `CASE` condition, or in a subscript,
 * rejects `null`.
 * The same rules apply outside an assignment, where no column is written. [ParameterNullabilityClassifier] states
 * the full rule.
 *
 * Each template writes the expected role of its placeholders, using these markers.
 * - `?` marks no opinion.
 * - `?v` marks a placeholder whose nullability the column decides.
 * - `?n` and `?x` mark a nullable and a non-null placeholder outside an assignment.
 * - `?N` and `?X` mark the same under an assignment, where the probe also names the column.
 *
 * The markers are removed before the probe runs, so the probe never sees the expectation.
 *
 * The slot `{c}` stands for the column `c`, or for the table `named_table` in `tableNameTemplates`. `{table}` stands
 * for each typed table in `typedTables`, whose `c` column is not `int`. Templates built by `largeTemplates` have more
 * than 100 placeholders, which the probe splits into chunks.
 */
@Testcontainers
class ParameterNullabilityTemplateTest {

  private val rootTemplates = listOf(
    "UPDATE t SET a = (SELECT max(u.x) FROM u WHERE u.id = 1), {c} = ?v WHERE {c} = ?x",
    "UPDATE t SET {c} = ?v, a = (SELECT max(u.x) FROM u WHERE u.{c} = ?x) WHERE {c} = ?x",
    "UPDATE t SET {c} = (SELECT max(u.x) FROM u WHERE u.{c} = ?x), a = ?v WHERE {c} = ?x",
    "UPDATE t SET {c} = ?v FROM u JOIN v ON v.{c} = ?x WHERE t.id = u.id AND u.{c} = ?x",
    "UPDATE t SET {c} = coalesce(?N, t.{c}), a = ?v WHERE {c} = ?x",
    "UPDATE t SET a = (SELECT max(u.x) FROM u WHERE u.id = 1), {c} = coalesce(?N, t.{c}) WHERE {c} = ?x",
    "WITH w AS (SELECT u.id FROM u WHERE u.{c} = ?x) UPDATE t SET {c} = ?v FROM w WHERE t.id = w.id AND {c} = ?x",
    "WITH w AS MATERIALIZED (SELECT u.id FROM u WHERE u.{c} = ?x) " +
      "UPDATE t SET {c} = ?v FROM w WHERE t.id = w.id AND {c} = ?x",
    "WITH w AS NOT MATERIALIZED (SELECT u.id FROM u WHERE u.{c} = ?x) " +
      "UPDATE t SET {c} = ?v FROM w WHERE t.id = w.id AND {c} = ?x",
    "WITH w AS (UPDATE u SET {c} = ?v WHERE {c} = ?x RETURNING u.id) " +
      "UPDATE t SET {c} = ?v FROM w WHERE t.id = w.id AND {c} = ?x",
    "WITH w AS (SELECT u.id FROM u WHERE u.{c} = ?x FOR UPDATE) " +
      "UPDATE t SET {c} = ?v FROM w WHERE t.id = w.id AND {c} = ?x",
    "WITH w1 AS (SELECT u.id FROM u WHERE u.{c} = ?x), " +
      "w2 AS MATERIALIZED (SELECT v.id FROM v WHERE v.{c} = ?x FOR UPDATE) " +
      "UPDATE t SET {c} = ?v FROM w1, w2 WHERE t.id = w1.id AND t.id = w2.id AND {c} = ?x",
    "WITH w AS (SELECT 1 AS id) UPDATE t SET {c} = ?v WHERE {c} = ?x",
    "SELECT t.id FROM t WHERE ({c} = ?x AND a IS DISTINCT FROM b) OR (update = ?x AND set = ?x AND b = ?x)",
    "SELECT t.id FROM t WHERE {c} = ?x AND a IS NOT DISTINCT FROM b AND ({c} = ?x)",
    "DELETE FROM t WHERE ({c} = ?x AND a IS DISTINCT FROM b) AND set = ?x AND update = ?x",
    "DELETE FROM t USING u WHERE t.id = u.id AND u.{c} = ?x AND t.{c} = ?x",
    "UPDATE t AS {c} SET {c} = ?v WHERE id = ?x",
    "UPDATE t tt SET {c} = ?v WHERE tt.{c} = ?x",
    "UPDATE ONLY t SET {c} = ?v WHERE {c} = ?x",
    "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.{c} = ?x THEN UPDATE SET {c} = ?v " +
      "WHEN MATCHED AND s.{c} = ?x THEN UPDATE SET {c} = ?v, a = ?v WHEN NOT MATCHED THEN DO NOTHING",
    "WITH w AS (SELECT 1 AS id) MERGE INTO t USING w ON t.id = w.id " +
      "WHEN MATCHED AND t.{c} = ?x THEN UPDATE SET {c} = ?v",
    "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET {c} = coalesce(?N, t.{c}) " +
      "WHEN NOT MATCHED AND s.{c} = ?x THEN INSERT (id) VALUES (?)",
    "MERGE INTO t USING s ON t.id = s.id AND t.{c} = ?x WHEN MATCHED THEN DO NOTHING",
    "UPDATE t SET U&\"{c}\" = ?v WHERE id = ?x",
    "UPDATE t SET U&\"{c}\" = coalesce(?N, t.{c}) WHERE id = ?x",
    "UPDATE t SET a = 1, U&\"{c}\" = ?v WHERE id = ?x",
    "UPDATE t SET arr = ARRAY[1, 2] WHERE {c} = ?x",
    "UPDATE t SET tags = ARRAY['x', 'y'] WHERE {c} = ?x",
    "UPDATE t SET arr = ARRAY[1, 2] FROM u WHERE t.id = u.id AND u.{c} = ?x",
    "UPDATE t SET arr = ARRAY[1, 2], {c} = ?v WHERE id = ?x",
    "UPDATE t SET arr[1] = 1, {c} = ?v WHERE id = ?x",
    "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.a > 0 THEN UPDATE SET arr = ARRAY[1, 2] " +
      "WHEN MATCHED AND t.{c} = ?x THEN DELETE",
    "SELECT t.id, set, {c} = ? FROM t",
    "SELECT ROW(update + a, set, {c} = ?) FROM t",
    "UPDATE t SET {c} = ?v WHERE a IN (SELECT u.id FROM u WHERE u.{c} = ?x)",
    "INSERT INTO t DEFAULT VALUES ON CONFLICT (id) DO UPDATE SET {c} = ?v WHERE t.{c} = ?x",
    "INSERT INTO t(id, {c}) VALUES (?v, ?v) ON CONFLICT (id) DO UPDATE SET {c} = ?v",
    "INSERT INTO t(id, {c}) VALUES (?v, ?v) ON CONFLICT (id) DO UPDATE SET {c} = coalesce(?N, t.{c}), a = ?v",
    "INSERT INTO t(id, {c}) VALUES (?v, ?v) ON CONFLICT (id) DO UPDATE SET a = ?v WHERE t.{c} = ?x",
    "INSERT INTO t(id) VALUES (?v) ON CONFLICT (id) DO UPDATE SET {c} = ?v RETURNING t.{c}, t.id",
    "INSERT INTO t(id, {c}) VALUES (?v, ?v), (?v, ?v), (?v, ?v)",
    "INSERT INTO t(id, {c}) VALUES (?v, ?v), (?v, ?v) ON CONFLICT (id) DO UPDATE SET {c} = coalesce(?N, t.{c})",
    "INSERT INTO t(id, arr_required) VALUES (?v, ARRAY[?N::int, 1]), (?v, ARRAY[1, ?N::int])",
    "INSERT INTO t(id, nn) VALUES (?v, ?X::int), (?v, ?X)",
    "UPDATE t SET {c} = ?v WHERE {c} = ?x RETURNING id, (SELECT 1 FROM u WHERE u.{c} = ?x), ({c} = ?)",
    "UPDATE t SET {c} = ?v WHERE merge = ?x AND materialized = ?x AND update = ?x AND set = ?x",
    "UPDATE t SET update = ?v, set = ?v, a = ?v WHERE {c} = ?x",
    "UPDATE t SET {c} = ?v WHERE EXISTS (SELECT 1 FROM u WHERE u.{c} = ?x AND (update = ?x AND set = ?x))",
    "UPDATE t SET ({c}, a) = (SELECT 1, 2), b = ?v WHERE id = ?x",
    "UPDATE t SET ({c}, a) = (SELECT ?::int, 2) WHERE id = ?x",
    "UPDATE t SET {c} = (SELECT ?::int) WHERE id = ?x",
    "SELECT t.id FROM t JOIN u ON u.{c} = ?x WHERE t.id = u.id",
    "SELECT t.id FROM t GROUP BY t.id HAVING max(t.{c}) = ?x",
  )

  private val nodeTemplates = listOf(
    "UPDATE t SET {c} = ?v::bigint WHERE id = ?x",
    "UPDATE t SET {c} = CAST(?v AS smallint) WHERE id = ?x",
    "UPDATE t SET {c} = (?v::text)::int WHERE id = ?x",
    "UPDATE t SET note = ?v COLLATE \"C\" WHERE {c} = ?x",
    "UPDATE t SET note = upper(?v) WHERE {c} = ?x",
    "UPDATE t SET note = concat(?v::text, 'x') WHERE {c} = ?x",
    "UPDATE t SET {c} = ?v + 1 WHERE id = ?x",
    "UPDATE t SET flag = (?v = ANY(arr)) WHERE id = ?x",
    "UPDATE t SET flag = (?v <> ALL(arr)) WHERE id = ?x",
    "UPDATE t SET flag = ({c} = ANY(?v)) WHERE id = ?x",
    "UPDATE t SET flag = ({c} <> ALL(?v)) WHERE id = ?x",
    "UPDATE t SET flag = {c} = ?v, a = ?v WHERE id = ?x",
    "UPDATE t SET a = (({c} = ?v)::int) WHERE id = ?x",
    "UPDATE t SET {c} = (SELECT ?::int) WHERE id = ?x",
    "UPDATE t SET nn = ?X::int WHERE {c} = ?x",
    "UPDATE t SET nn = coalesce(?N, 1) WHERE {c} = ?x",
    "UPDATE t SET d2col = ?X WHERE {c} = ?x",
    "UPDATE t SET flag = ?X::nnint IS NULL WHERE {c} = ?x",
    "UPDATE t SET dp.f = ?X WHERE {c} = ?x",
    "UPDATE t SET flag = ?N::int IS NULL WHERE {c} = ?x",
    "UPDATE t SET flag = (?N IS TRUE) WHERE {c} = ?x",
    "UPDATE t SET flag = ?N IS DISTINCT FROM 1 WHERE {c} = ?x",
    "UPDATE t SET flag = ?N IS NOT DISTINCT FROM 1 WHERE {c} = ?x",
    "UPDATE t SET w = ?v::text WHERE {c} = ?x",
    "SELECT t.id FROM t WHERE {c} = ?x OR ?n::int IS NULL",
    "SELECT t.id FROM t WHERE {c} = ?x OR NOT ({c} = ?x)",
    "UPDATE t SET flag = ({c} = ?v OR flag) WHERE id = ?x",
    "UPDATE t SET flag = coalesce({c} = ?N, true) WHERE id = ?x",
    "UPDATE t SET flag = coalesce(null, {c} = ?v) WHERE id = ?x",
    "UPDATE t SET a = coalesce(?N, a), {c} = ?v WHERE id = ?x",
    "UPDATE t SET {c} = coalesce(?N, {c}), a = (({c} < ?v)::int) WHERE id = ?x",
    "UPDATE t SET {c} = coalesce(?N, ?N, 0) WHERE id = ?x",
    "UPDATE t SET {c} = coalesce(?N, 0) + 1 WHERE id = ?x",
    "UPDATE t SET {c} = nullif(?v, 0) WHERE id = ?x",
    "UPDATE t SET {c} = nullif(1, ?N) WHERE id = ?x",
    "UPDATE t SET {c} = greatest(?v, 1) WHERE id = ?x",
    "UPDATE t SET {c} = least(1, ?v) WHERE id = ?x",
    "UPDATE t SET {c} = CASE WHEN ?X THEN 1 END WHERE id = ?x",
    "UPDATE t SET {c} = CASE ?X::int WHEN 1 THEN 2 END WHERE id = ?x",
    "UPDATE t SET {c} = CASE WHEN b = 1 THEN ?v::int END WHERE id = ?x",
    "UPDATE t SET {c} = CASE WHEN flag THEN ?v::int ELSE ?v::int END WHERE id = ?x",
    "UPDATE t SET a = CASE WHEN {c} = ?X THEN 1 END, {c} = ?v WHERE id = ?x",
    "UPDATE t SET a = CASE WHEN {c} = 1 THEN {c} ELSE 1 END, {c} = ?v WHERE {c} = ?x",
    "UPDATE t SET a = CASE WHEN b = 1 THEN update ELSE set END, {c} = ?v WHERE id = ?x",
    "UPDATE t SET arr[?X] = ?N WHERE {c} = ?x",
    "UPDATE t SET arr[?X:?X] = ARRAY[1, 2] WHERE {c} = ?x",
    "UPDATE t SET arr_required[1] = ?N WHERE {c} = ?x",
    "UPDATE t SET a = arr[?v] WHERE {c} = ?x",
    "UPDATE t SET a = (ARRAY[?v, 2])[1] WHERE {c} = ?x",
    "UPDATE t SET flag = 1 = ANY(ARRAY[?v, 2]) WHERE {c} = ?x",
    "UPDATE t SET {c} = coalesce(null, (ARRAY[?v::int, 2])[1]) WHERE id = ?x",
    "UPDATE t SET arr = ARRAY[?N, 2] WHERE {c} = ?x",
    "UPDATE t SET arr_required = ARRAY[?N::int, ?N::int] WHERE {c} = ?x",
    "UPDATE ta SET {c} = ARRAY[?N::text, 'x'] WHERE id = ?x",
    "UPDATE t SET arr_required = coalesce(null, ARRAY[?v::int, 2]) WHERE {c} = ?x",
    "UPDATE t SET arr_required = CASE WHEN flag THEN ARRAY[?v::int, 2] ELSE '{}' END WHERE {c} = ?x",
    "UPDATE t SET arr_required = greatest(ARRAY[?v::int, 2], arr) WHERE {c} = ?x",
    "SELECT t.id, coalesce(?n::int, a) FROM t WHERE {c} = ?x",
    "SELECT t.id FROM t ORDER BY {c} = coalesce(?n, {c})",
    "SELECT ?n::int IS NULL, ?n::int IS DISTINCT FROM 1, nullif(1, ?n::int) FROM t WHERE {c} = ?x",
    "SELECT max(coalesce(?n::int, {c})) FROM t",
    "SELECT * FROM (VALUES (coalesce(?n::int, 1))) AS v(m)",
    "UPDATE t SET {c} = ?v WHERE id = ?x RETURNING coalesce(?n::int, {c})",
    "UPDATE t SET {c} = (SELECT arr[?] FROM u WHERE u.id = ?x) WHERE id = ?x",
    "UPDATE ct SET {c}.f = ?N WHERE id = ?x",
    "UPDATE ct SET {c}.f = coalesce(?N, 0), {c}.g = ?N WHERE id = ?x",
    "UPDATE cn SET {c}.f = ?X, {c}.g = ?N WHERE id = ?x",
    "UPDATE cn SET {c}.f = coalesce(?N, 1), {c}.g = ?N WHERE id = ?x",
    "UPDATE ct SET {c} = ROW(?N::int, 1) WHERE id = ?x",
    "UPDATE t SET tags = ARRAY[?N::text, 'x'] WHERE {c} = ?x",
    "UPDATE t SET tags_required = ARRAY[?N::text, 'x'] WHERE {c} = ?x",
    "UPDATE t SET {c} = ?v WHERE {c} = ANY(ARRAY[?x::int, ?x::int])",
    "UPDATE t SET flag = (?v IN (SELECT u.{c} FROM u WHERE u.id = ?x)) WHERE id = ?x",
    "UPDATE t SET flag = ?v::int IN (SELECT u.{c} FROM u) WHERE id = ?x",
    "UPDATE t SET {c} = ?v WHERE id = ?x RETURNING {c}",
    "UPDATE t SET ({c}, a) = (?v, ?v) WHERE id = ?x",
  )

  private val largeTemplates = listOf(
    "UPDATE t SET {c} = ?v WHERE id IN (${placeholders(100)})",
    "UPDATE t SET {c} = ?v WHERE id IN (${placeholders(249)})",
    "UPDATE t SET a = (SELECT max(u.x) FROM u WHERE u.id IN (${placeholders(120)})), {c} = ?v WHERE id = ?x",
    "UPDATE t SET a = (SELECT max(u.x) FROM u WHERE u.id IN (${placeholders(230)})), " +
      "{c} = coalesce(?N, t.{c}), b = ?v WHERE id = ?x",
    "UPDATE t SET b = ?v WHERE {c} IN (${placeholders(100)})",
  )

  private val typedTemplates = listOf(
    "UPDATE {table} SET {c} = ?v WHERE id = ?x",
    "UPDATE {table} SET {c} = coalesce(?N, {table}.{c}) WHERE id = ?x",
    "UPDATE {table} SET a = (SELECT 1 FROM u WHERE u.id = ?x), {c} = ?v WHERE id = ?x",
    "UPDATE {table} SET {c} = ?v FROM u JOIN v ON v.x = ?x WHERE {table}.id = u.id AND u.y = ?x",
    "INSERT INTO {table}(id, {c}) VALUES (?v, ?v) ON CONFLICT (id) DO UPDATE SET {c} = ?v",
    "INSERT INTO {table}(id, {c}) VALUES (?v, ?v), (?v, ?v)",
    "INSERT INTO {table}(id, {c}) VALUES (?v, ?v) ON CONFLICT (id) DO UPDATE SET {c} = coalesce(?N, {table}.{c})",
    "MERGE INTO {table} USING s ON {table}.id = s.id WHEN MATCHED AND s.x = ?x THEN UPDATE SET {c} = ?v",
    "WITH w AS (UPDATE {table} SET {c} = ?v RETURNING id) SELECT id FROM w WHERE id = ?x",
  )

  private val typedTables = mapOf(
    "tv" to TypedTable("varchar(10)", listOf("?::varchar", "?::text", "CAST(? AS varchar(5))")),
    "tn" to TypedTable("numeric(8, 2)", listOf("?::numeric", "?::int", "?::numeric(5, 1)")),
    "tc" to TypedTable("char(4)", listOf("?::char(2)", "?::text", "?::bpchar")),
    "tts" to TypedTable("timestamp(3)", listOf("?::timestamp", "?::date", "?::timestamp(1)")),
    "td" to TypedTable("posint", listOf("?::int", "?::posint", "?::smallint")),
    "ta" to TypedTable("varchar(4)[]", listOf("?::varchar[]", "?::text[]", "?::varchar(2)[]")),
  )

  private val tableNameTemplates = listOf(
    "UPDATE {c} SET a = ?v WHERE id = ?x",
    "UPDATE ONLY {c} SET a = ?v WHERE id = ?x",
    "UPDATE public.{c} SET a = ?v WHERE id = ?x",
    "UPDATE {c} tt SET a = ?v WHERE tt.id = ?x",
    "UPDATE {c} AS tt SET a = coalesce(?N, tt.a) WHERE id = ?x",
    "INSERT INTO {c}(id) VALUES (?v) ON CONFLICT (id) DO UPDATE SET a = ?v",
    "MERGE INTO {c} USING s ON {c}.id = s.id WHEN MATCHED THEN UPDATE SET a = ?v",
  )

  @Test
  fun `the probe classifies every placeholder as the template markers expect`() {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
      createTables(connection)
      val plainTemplates = (rootTemplates + nodeTemplates + largeTemplates).map(::parseTemplate)
      val typedInstances = typedTables.flatMap { (table, typedTable) ->
        (typedTemplates + castTemplates(typedTable)).map { parseTemplate(it.replace("{table}", table)) }
      }
      val instances = (plainTemplates + typedInstances).map { it.instantiate(COLUMN) } +
        tableNameTemplates.map { parseTemplate(it).instantiate(TABLE, expectedTable = TABLE) }
      val probe = ParameterNullabilityProbe(connection)
      val failures = mutableListOf<String>()
      for (instance in instances) verify(connection, probe, instance, failures)
      println("ParameterNullabilityTemplateTest: ${instances.size} instances, ${failures.size} failures")
      assertThat(failures.take(MAX_REPORTED_FAILURES)).isEmpty()
    }
  }

  private fun castTemplates(typedTable: TypedTable): List<String> = typedTable.casts.flatMap { cast ->
    listOf(
      "UPDATE {table} SET {c} = ${cast.replace("?", "?v")} WHERE id = ?x",
      "UPDATE {table} SET {c} = coalesce(${cast.replace("?", "?N")}, {table}.{c}) WHERE id = ?x",
    )
  }

  private data class TypedTable(val columnType: String, val casts: List<String>)

  private enum class Role {
    NO_OPINION,
    INHERIT,
    NULLABLE,
    NON_NULL,
    NULLABLE_IN_ASSIGNMENT,
    NON_NULL_IN_ASSIGNMENT,
  }

  private fun Role.isUnderAssignment() = this == Role.NULLABLE_IN_ASSIGNMENT || this == Role.NON_NULL_IN_ASSIGNMENT

  private fun Role.nullabilityRole() = when (this) {
    Role.NULLABLE_IN_ASSIGNMENT -> Role.NULLABLE
    Role.NON_NULL_IN_ASSIGNMENT -> Role.NON_NULL
    else -> this
  }

  private fun placeholders(count: Int): String = List(count) { "?x" }.joinToString(", ")

  private class Template(private val text: String, val roles: List<Role>) {
    fun instantiate(name: String, expectedTable: String? = null): Instance =
      Instance(text.replace("{c}", name), roles, expectedTable)
  }

  private class Instance(val sql: String, val roles: List<Role>, val expectedTable: String?)

  private fun parseTemplate(markedText: String): Template {
    val roles = mutableListOf<Role>()
    val text = StringBuilder()
    var index = 0
    while (index < markedText.length) {
      val character = markedText[index++]
      text.append(character)
      if (character != '?') continue
      val role = when (markedText.getOrNull(index)) {
        'v' -> Role.INHERIT
        'n' -> Role.NULLABLE
        'x' -> Role.NON_NULL
        'N' -> Role.NULLABLE_IN_ASSIGNMENT
        'X' -> Role.NON_NULL_IN_ASSIGNMENT
        else -> Role.NO_OPINION
      }
      if (role != Role.NO_OPINION) index++
      roles += role
    }
    return Template(text.toString(), roles)
  }

  private fun createTables(connection: Connection) {
    val columns = listOf(
      "id int primary key", "a int", "b int", "x int", "y int", "flag boolean", "arr int[]",
      "arr_required int[] NOT NULL DEFAULT '{}'", "tags text[]", "tags_required text[] NOT NULL DEFAULT '{}'",
      "w widget", "nn nnint", "d2col d2", "dp dpair", "note text", "$COLUMN int",
      "update int", "set int", "merge int", "materialized int",
    ).joinToString(", ")
    connection.createStatement().use { statement ->
      statement.execute("CREATE DOMAIN posint AS int CHECK (VALUE > 0)")
      statement.execute("CREATE DOMAIN nnint AS int NOT NULL")
      statement.execute("CREATE DOMAIN d1 AS int NOT NULL")
      statement.execute("CREATE DOMAIN d2 AS d1")
      statement.execute("CREATE TYPE widget AS (v text)")
      statement.execute("CREATE TYPE pair AS (f int, g int)")
      statement.execute("CREATE TYPE nnpair AS (f nnint, g int)")
      statement.execute("CREATE TYPE dpair AS (f d2)")
      statement.execute(
        "CREATE FUNCTION text_to_widget(text) RETURNS widget LANGUAGE sql CALLED ON NULL INPUT " +
          "AS \$\$ SELECT ROW(\$1)::widget \$\$",
      )
      statement.execute("CREATE CAST (text AS widget) WITH FUNCTION text_to_widget(text) AS ASSIGNMENT")
      for ((table, typedTable) in typedTables) {
        val typedColumns = listOf("id int primary key", "a int", "$COLUMN ${typedTable.columnType}")
        statement.execute("CREATE TABLE $table (${typedColumns.joinToString(", ")})")
      }
      for (table in listOf("t", "u", "v", "s")) statement.execute("CREATE TABLE $table ($columns)")
      statement.execute("CREATE TABLE $TABLE (id int primary key, a int)")
      for ((table, type) in mapOf("ct" to "pair", "cn" to "nnpair")) {
        statement.execute("CREATE TABLE $table (id int primary key, a int, $COLUMN $type)")
      }
    }
  }

  private fun verify(
    connection: Connection,
    probe: ParameterNullabilityProbe,
    instance: Instance,
    failures: MutableList<String>,
  ) {
    val sql = instance.sql
    check(placeholderPositions(sql).size == instance.roles.size) { "Template marks the wrong placeholder count: $sql" }
    val classified = try {
      probe.classify(sql)
    } catch (exception: SQLException) {
      failures += "probe threw (${exception.message?.lineSequence()?.first()}) — $sql"
      return
    }
    createsProbeFunction(connection, sql)?.let { failures += "PostgreSQL rejected the probe function ($it) — $sql" }
    for ((index, expectedRole) in instance.roles.withIndex()) {
      val parameter = index + 1
      val actual = classified[parameter]
      val actualRole = when (actual?.nullability) {
        null -> Role.NO_OPINION
        is ParameterNullability.Inherit -> Role.INHERIT
        ParameterNullability.Nullable -> Role.NULLABLE
        ParameterNullability.NonNull -> Role.NON_NULL
      }
      if (actualRole != expectedRole.nullabilityRole()) {
        failures += "parameter $parameter expected $expectedRole, probe classified $actualRole — $sql"
      } else if (expectedRole.isUnderAssignment() && actual?.name.isNullOrBlank()) {
        failures += "parameter $parameter under an assignment has no column name ($actual) — $sql"
      } else if (actualRole == Role.INHERIT) {
        val inherited = (actual?.nullability as ParameterNullability.Inherit).column
        if (actual.name.isNullOrBlank()) {
          failures += "parameter $parameter has no target column ($actual) — $sql"
        } else if (instance.expectedTable != null && inherited.table != instance.expectedTable) {
          failures += "parameter $parameter targets table ${inherited.table}, expected ${instance.expectedTable} — $sql"
        }
      }
    }
    val unknownParameters = classified.keys.filter { it !in 1..instance.roles.size }
    if (unknownParameters.isNotEmpty()) failures += "probe classified nonexistent parameters $unknownParameters — $sql"
  }

  /**
   * Creates the function the probe compiles [sql] into, so an instance with no expected opinion still proves that
   * PostgreSQL accepted it.
   *
   * @return the error message, `null` when PostgreSQL accepted the function.
   */
  private fun createsProbeFunction(connection: Connection, sql: String): String? {
    val statementName = "norm_template_check"
    connection.createStatement().use {
      it.execute("PREPARE $statementName AS ${replaceParameterPlaceholders(sql) { index -> "\$${index + 1}" }}")
    }
    try {
      val types = connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT parameter_types::text[] FROM pg_prepared_statements WHERE name = '$statementName'",
        ).use { resultSet ->
          resultSet.next()
          @Suppress("UNCHECKED_CAST")
          (resultSet.getArray(1).array as Array<String>).toList()
        }
      }
      for (chunk in types.indices.chunked(ParameterNullabilityProbe.MAX_FUNCTION_ARGUMENTS)) {
        val body = replaceParameterPlaceholders(sql) { index ->
          if (index in chunk) "\$${index - chunk.first() + 1}" else "CAST(NULL AS ${types[index]})"
        }
        val signature = chunk.joinToString(", ") { types[it] }
        var rejection: String? = null
        withProsqlbodyNodeTree(connection, body, signature, "void", onRejectedCreate = { rejection = it.message }) { }
        if (rejection != null) return rejection?.lineSequence()?.first()
      }
      return null
    } finally {
      connection.createStatement().use { it.execute("DEALLOCATE $statementName") }
    }
  }

  companion object {
    private const val MAX_REPORTED_FAILURES = 25
    private const val COLUMN = "c"
    private const val TABLE = "named_table"

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> =
      testPostgresContainer("norm_parameter_nullability_templates", inMemory = true)
  }
}
