package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.SQLException

/**
 * Loads schema metadata from PostgreSQL's system catalogs via JDBC.
 *
 * Provides a lazy-loaded cache of [functionOverloads] computed once per instance, plus
 * on-demand queries for enums, domains, column comments, and stored procedures. Function
 * strictness, safe-list, and column-nullability facts live on the composed [NullabilityCatalog]
 * instead, and per-query nullability analysis on the composed [ColumnNullabilityAnalyzer] — see
 * each class's own KDoc for why its concern is split out from this one.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
internal class PgCatalogLoader(private val connection: Connection) {

  private val pgMajorVersion = connection.metaData.databaseMajorVersion

  private val nullabilityCatalog = NullabilityCatalog(connection)

  private val nullabilityAnalyzer = ColumnNullabilityAnalyzer(connection, nullabilityCatalog)

  init {
    checkPostgresVersion()
  }

  /**
   * Maps function names to their overload metadata from `pg_proc`.
   *
   * Used to infer parameter names and result nullability for queries that pass parameters to
   * function calls. Functions may be overloaded, so we store a list of overloads per function
   * name, matched by argument count at inference time.
   *
   * Loaded lazily on first use and cached for the lifetime of this loader.
   */
  val functionOverloads: Map<String, List<FunctionOverload>> by lazy(::loadFunctionOverloads)

  private fun loadFunctionOverloads(): Map<String, List<FunctionOverload>> =
    buildMap<String, MutableList<FunctionOverload>> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
      SELECT p.proname, p.proargnames, p.pronargs, p.proisstrict
      FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
      WHERE p.prokind IN ('f', 'p')
        AND p.pronargs > 0
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            val name = rs.getString("proname")
            val argNamesArray = rs.getArray("proargnames")
            val argNames = if (argNamesArray != null) {
              @Suppress("UNCHECKED_CAST")
              (argNamesArray.array as Array<String>).toList()
            } else {
              emptyList()
            }
            val isStrict = rs.getBoolean("proisstrict")
            computeIfAbsent(name) { mutableListOf() }.add(FunctionOverload(argNames, isStrict))
          }
        }
      }
    }

  /**
   * Returns NOT NULL column information for views and materialized views in [schemaName], for
   * [JdbcAnalyzer]'s catalog construction.
   *
   * A thin name-resolution adapter over [ColumnNullabilityAnalyzer.isColumnNotNull], the single
   * relid-keyed source of truth for column nullability, base table and view alike. Unlike the
   * `pg_depend` name-join this replaces, a view column's answer comes from fully evaluating the
   * view's own defining query rather than from tracing a same-named source column and inheriting
   * its constraint. This function does no computation of its own.
   *
   * @param schemaName The schema to check.
   * @return A set of `"viewName.columnName"` strings for view/matview columns that are non-nullable.
   */
  fun loadViewColumnNullability(schemaName: String): Set<String> = loadViewColumnNamesByRelidAndAttnum(schemaName)
    .filterKeys { key -> nullabilityAnalyzer.isColumnNotNull(key) }
    .values.toSet()

  /**
   * Maps `(relid, attnum)` to `"viewName.columnName"` for every view/matview column in
   * [schemaName] — the name-resolution half of [loadViewColumnNullability]'s adapter over
   * [ColumnNullabilityAnalyzer.isColumnNotNull].
   *
   * `ORDER BY c.oid, a.attnum` matters, not just style: [loadViewColumnNullability] resolves
   * these rows one at a time through a single shared [ColumnNullabilityAnalyzer], whose answer for a
   * view deep enough to hit [ColumnNullabilityAnalyzer.VIEW_NULLABILITY_RECURSION_DEPTH_BUDGET] can
   * depend on which views were memoized beforehand. Absent an `ORDER BY`, PostgreSQL may return these
   * rows in any order, so the generated Kotlin type for a very deep view chain could differ between
   * two runs of the same build. A fixed scan order makes it reproducible for a given schema.
   */
  private fun loadViewColumnNamesByRelidAndAttnum(schemaName: String): Map<Pair<Int, Int>, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.oid::integer AS relid, a.attnum, c.relname AS view_name, a.attname AS column_name
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE n.nspname = '$schemaName'
          AND c.relkind IN ('v', 'm')
        ORDER BY c.oid, a.attnum
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(
            rs.getInt("relid") to rs.getInt("attnum"),
            "${rs.getString("view_name")}.${rs.getString("column_name")}",
          )
        }
      }
    }
  }

  /**
   * Returns the names of partition children in [schemaName].
   *
   * Partition children (e.g., `event_2026 PARTITION OF event`) are implementation details of partitioned
   * tables. They appear as regular `"TABLE"` entries in JDBC metadata but should be excluded from the catalog
   * because users query the parent table, not individual partitions.
   *
   * @param schemaName The schema to check for partitions.
   * @return A set of table names that are partition children and should be excluded from the catalog.
   */
  fun loadPartitionChildren(schemaName: String): Set<String> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND c.relispartition = true
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          add(rs.getString("relname"))
        }
      }
    }
  }

  /**
   * Returns comments for all table-like relations in [schemaName], keyed by relation name.
   *
   * Includes tables, partitioned tables, views, and materialized views.
   * Comments are set with `COMMENT ON TABLE|VIEW|MATERIALIZED VIEW name IS '...'` in DDL.
   *
   * @param schemaName The schema to load comments for.
   * @return A map from relation name to the comment text. Relations without comments are absent.
   */
  fun loadTableComments(schemaName: String): Map<String, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname AS table_name, d.description
        FROM pg_catalog.pg_description d
        JOIN pg_catalog.pg_class c ON c.oid = d.objoid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND d.objsubid = 0
          AND c.relkind IN ('r', 'p', 'v', 'm')
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(rs.getString("table_name"), rs.getString("description"))
        }
      }
    }
  }

  /**
   * Returns column comments for all tables in [schemaName], keyed by `"tableName.columnName"`.
   *
   * Comments are set with `COMMENT ON COLUMN table.col IS '...'` in DDL.
   *
   * @param schemaName The schema to load comments for.
   * @return A map from `"table.column"` to the comment text. Columns without comments are absent.
   */
  fun loadColumnComments(schemaName: String): Map<String, String> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT c.relname AS table_name, a.attname AS column_name, d.description
        FROM pg_catalog.pg_description d
        JOIN pg_catalog.pg_class c ON c.oid = d.objoid
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schemaName'
          AND d.objsubid > 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put("${rs.getString("table_name")}.${rs.getString("column_name")}", rs.getString("description"))
        }
      }
    }
  }

  /**
   * Returns all enum types defined in [schemaName], with their labels in declaration order.
   *
   * Comments are set with `COMMENT ON TYPE enumname IS '...'` in DDL.
   *
   * @param schemaName The schema to introspect.
   * @return One [Enum] per type, with [Enum.vals] ordered by `enumsortorder` and [Enum.comment] if present.
   */
  fun introspectEnums(schemaName: String): List<Enum> {
    val enumsByName = buildMap<String, MutableList<String>> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
            SELECT t.typname, e.enumlabel
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_enum e ON t.oid = e.enumtypid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = '$schemaName'
            ORDER BY t.typname, e.enumsortorder
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            computeIfAbsent(rs.getString("typname")) { mutableListOf() }.add(rs.getString("enumlabel"))
          }
        }
      }
    }

    val enumComments = buildMap<String, String> {
      connection.createStatement().use { stmt ->
        stmt.executeQuery(
          """
            SELECT t.typname, d.description
            FROM pg_catalog.pg_description d
            JOIN pg_catalog.pg_type t ON t.oid = d.objoid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = '$schemaName'
              AND t.typtype = 'e'
          """.trimIndent(),
        ).use { rs ->
          while (rs.next()) {
            put(rs.getString("typname"), rs.getString("description"))
          }
        }
      }
    }

    return enumsByName.map { (name, values) ->
      Enum(
        name = name,
        vals = values,
        comment = enumComments[name].orEmpty(),
      )
    }
  }

  /**
   * Returns all domain types defined in [schemaName], with their base types and optional comments.
   *
   * PostgreSQL domains (e.g., `CREATE DOMAIN email AS TEXT`) are user-defined types that wrap a
   * base type with optional constraints. Comments are set with `COMMENT ON DOMAIN name IS '...'`.
   *
   * @param schemaName The schema to introspect.
   * @return One [Domain] per domain type, with [Domain.baseType] as the Postgres base type name
   *   and [Domain.comment] if present.
   */
  fun introspectDomains(schemaName: String): List<Domain> = buildList {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT t.typname AS domain_name, bt.typname AS baseType, d.description
        FROM pg_catalog.pg_type t
        JOIN pg_catalog.pg_type bt ON t.typbasetype = bt.oid
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        LEFT JOIN pg_catalog.pg_description d ON d.objoid = t.oid AND d.objsubid = 0
        WHERE n.nspname = '$schemaName'
          AND t.typtype = 'd'
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          add(
            Domain(
              name = rs.getString("domain_name"),
              baseType = rs.getString("baseType"),
              comment = rs.getString("description").orEmpty(),
            ),
          )
        }
      }
    }
  }

  /**
   * Determines which result columns of [sql] can be `NULL`.
   *
   * Routes every statement — a plain `SELECT` exactly the same as a data-modifying statement or
   * CTE — through [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody]. `prosqlbody`
   * holds the identical post-parse-analysis `{QUERY ...}` shape `CREATE VIEW`'s `pg_rewrite.ev_action`
   * does (see that method's KDoc), so a plain `SELECT` needs no separate route of its own: on
   * PostgreSQL 16-18, every shape `CREATE VIEW` accepts but a SQL-standard function body might
   * plausibly reject or reinterpret — `UNION`/`INTERSECT`/`EXCEPT`, `WITH RECURSIVE`,
   * `ORDER BY`/`LIMIT`/`OFFSET`, `FOR UPDATE`/`FOR SHARE`, `DISTINCT ON`, a `VALUES` list, a
   * set-returning function in the target list, `LATERAL`, `TABLESAMPLE`, `WITH ORDINALITY`, and a
   * query selecting from another view — agrees between the two routes (see [QueryAnalysisTest]'s
   * `SELECT DISTINCT ON` and `TABLESAMPLE` cases, the two shapes the corpus had no other coverage
   * for; every other shape is exercised elsewhere in [QueryAnalysisTest] and in the
   * `test-scenarios` golden-file corpus, which pins the exact generated Kotlin type derived from
   * this function's answer).
   *
   * @return one [ColumnAnalysis] per result column. If
   *   [ColumnNullabilityAnalyzer.queryColumnNullabilityViaProsqlbody] cannot produce an answer at
   *   all — a probe failure, or [sql] is a `MERGE` whose `USING` clause has more than one source
   *   relation of its own (see [ColumnNullabilityAnalyzer]'s `mergeAbsentVarnos` KDoc) — every real
   *   result column (via `PreparedStatement.getMetaData()`, the only source of a column count this
   *   deep into a fallback) is reported nullable with no provenance: the safe direction, and
   *   consistent with every other fallback in this file. A statement with no result columns at all
   *   (`INSERT`/`UPDATE`/`DELETE`/`MERGE` with no `RETURNING`) naturally reports an empty list here,
   *   since its real column count is `0` — there is nothing for a caller to treat as nullable or
   *   not.
   */
  fun queryColumnNullability(@Language("PostgreSQL") sql: String): List<ColumnAnalysis> =
    nullabilityAnalyzer.queryColumnNullabilityViaProsqlbody(sql)
      ?: List(realColumnCount(sql)) { ColumnAnalysis(nullable = true, provenanceExpression = null) }

  /**
   * The real number of result columns [sql] produces, via `PreparedStatement.getMetaData()` — used
   * only by [queryColumnNullability]'s final, otherwise-blind fallback to size its all-nullable
   * default correctly (in particular, `0` for a `RETURNING`-less `INSERT`/`UPDATE`/`DELETE`/
   * `MERGE`, which must report an empty list, not a list of one `true` per some guessed count).
   */
  private fun realColumnCount(@Language("PostgreSQL") sql: String): Int = try {
    connection.prepareStatement(sql).use { it.metaData?.columnCount ?: 0 }
  } catch (_: SQLException) {
    0
  }

  /**
   * Checks that the connected PostgreSQL server is version 16 or later.
   *
   * Norm requires PostgreSQL 16+ for `varnullingrels` support in query tree nodes,
   * used for accurate outer join nullability detection.
   *
   * @throws IllegalStateException if the server version is below 16.
   */
  fun checkPostgresVersion() {
    check(pgMajorVersion >= 16) {
      "Norm requires PostgreSQL 16 or later (connected to version $pgMajorVersion). " +
        "PostgreSQL 16 added varnullingrels to query tree nodes, which Norm uses " +
        "for accurate outer join nullability detection."
    }
  }

  /**
   * Looks up the parameter types and names for a stored procedure from `pg_proc`.
   *
   * Used to generate typed parameters for `CALL` statements. Queries the `public` schema only.
   *
   * Argument names come from `proargnames`. When a procedure has no named arguments,
   * names fall back to `p1`, `p2`, etc.
   *
   * @param procName The unqualified procedure name to look up.
   * @return The procedure's parameters in declaration order, or an empty list if the procedure
   *   is not found in `pg_proc`.
   */
  fun lookupProcedureParameters(procName: String): List<Parameter> = buildList {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT p.proargnames, p.proargtypes, string_agg(t.typname, ',' ORDER BY ordinality) as type_names
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        CROSS JOIN LATERAL unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
        JOIN pg_catalog.pg_type t ON t.oid = u.type_oid
        WHERE p.proname = '$procName'
          AND n.nspname = 'public'
        GROUP BY p.proargnames, p.proargtypes
        """.trimIndent(),
      ).use { rs ->
        if (rs.next()) {
          val typeNames = rs.getString("type_names").split(",")
          val argNamesArray = rs.getArray("proargnames")
          val argNames = if (argNamesArray != null) {
            @Suppress("UNCHECKED_CAST")
            (argNamesArray.array as Array<String>).toList()
          } else {
            typeNames.indices.map { "p${it + 1}" }
          }
          for ((index, typeName) in typeNames.withIndex()) {
            add(
              Parameter(
                number = index + 1,
                column = Column(
                  name = argNames.getOrElse(index) { "p${index + 1}" },
                  notNull = true,
                  type = Identifier(name = typeName),
                ),
              ),
            )
          }
        }
      }
    }
  }
}

/**
 * One `pg_catalog` function signature safe-listed as TOTAL on non-null input — see
 * [NeverNullSafeLists.NEVER_NULL_FUNCTION_SIGNATURES] for the audited list and the reasoning
 * behind each entry.
 *
 * @property name The unqualified `pg_proc.proname`.
 * @property argumentTypeNames The exact, ordered list of `pg_type.typname` values for this
 *   overload's declared argument types — e.g. `listOf("text", "text")` for the two-argument `text`
 *   overload of a name. Empty for a zero-argument function (e.g. `now()`).
 */
internal data class SafeFunctionSignature(val name: String, val argumentTypeNames: List<String>)

/**
 * One `pg_catalog` cast signature safe-listed as TOTAL on non-null input — see
 * [NeverNullSafeLists.NEVER_NULL_CAST_SIGNATURES] for the audited list and the reasoning behind each
 * entry.
 *
 * @property sourceTypeName The `pg_type.typname` of `pg_cast.castsource`.
 * @property targetTypeName The `pg_type.typname` of `pg_cast.casttarget`.
 */
internal data class SafeCastSignature(val sourceTypeName: String, val targetTypeName: String)

/**
 * One `pg_catalog` operator signature safe-listed as TOTAL on non-null input — see
 * [NeverNullSafeLists.NEVER_NULL_OPERATOR_SIGNATURES] for the audited list and the reasoning behind
 * each entry.
 *
 * @property symbol The `pg_operator.oprname`.
 * @property leftTypeName The `pg_type.typname` of `pg_operator.oprleft`, or `null` if the operator
 *   has no left operand (a right-unary / prefix operator, `pg_operator.oprleft = 0`).
 * @property rightTypeName The `pg_type.typname` of `pg_operator.oprright`, or `null` if the
 *   operator has no right operand (a left-unary / postfix operator, `pg_operator.oprright = 0`).
 */
internal data class SafeOperatorSignature(val symbol: String, val leftTypeName: String?, val rightTypeName: String?)

/**
 * Metadata about a PostgreSQL function overload from `pg_proc`.
 *
 * Functions may be overloaded (same name, different argument counts), so [PgCatalogLoader] stores
 * a list of overloads per function name, matched by argument count at inference time.
 *
 * @property argNames The formal argument names. Empty when the function has no named arguments
 *   (common for C-implemented extension functions like pgcrypto's `digest`). The caller uses the
 *   function name itself as a fallback in that case.
 * @property isStrict Whether the function returns `null` when any argument is `null` (`STRICT` /
 *   `RETURNS NULL ON NULL INPUT`). When `true`, a call with all non-null arguments is non-null.
 */
internal data class FunctionOverload(val argNames: List<String>, val isStrict: Boolean)
