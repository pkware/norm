package norm.generator

import plugin.Column
import plugin.Domain
import plugin.Enum
import plugin.Identifier
import plugin.Parameter
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Loads schema metadata from PostgreSQL's system catalogs via JDBC.
 *
 * Provides a lazy-loaded cache of [functionOverloads] computed once per instance, plus
 * on-demand queries for enums, domains, column comments, and stored procedures.
 *
 * @param connection An open JDBC connection to a PostgreSQL database with the schema applied.
 */
internal class PgCatalogLoader(private val connection: Connection) {

  private val nodeTreeParser = PgNodeTreeParser()

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

  /**
   * Maps function OIDs to their strictness flag from `pg_proc.proisstrict`.
   *
   * A strict function returns `null` when any argument is `null` — useful for determining
   * expression nullability from the node tree. Keyed by OID for direct lookup from
   * FUNCEXPR/OPEXPR/WINDOWFUNC nodes. Includes regular functions (`prokind = 'f'`) and
   * window functions (`prokind = 'w'`). Excludes aggregates (`prokind = 'a'`) — those
   * use [aggregateHasNonNullInitialValue] instead.
   */
  val functionStrictnessByOid: Map<Int, Boolean> by lazy(::loadFunctionStrictness)

  /**
   * Maps aggregate function OIDs to whether they have a non-null initial transition value.
   *
   * Aggregates with non-null `agginitval` (like COUNT with `agginitval = '0'`) return a
   * non-null value for empty groups. Aggregates with `null` `agginitval` (SUM, AVG, MIN, MAX)
   * return `null` for empty groups.
   *
   * Returns `null` for absent keys — this can occur if the OID belongs to a non-aggregate function.
   */
  val aggregateHasNonNullInitialValue: Map<Int, Boolean> by lazy(::loadAggregateInitialValues)

  /**
   * Maps `(relid, attnum)` pairs to `pg_attribute.attnotnull`.
   *
   * Used by [NodeTreeNullabilityAnalyzer] to determine whether a source column (referenced
   * by a VAR node) is declared NOT NULL in the schema. Only includes user-visible columns
   * (`attnum > 0` and `NOT attisdropped`).
   *
   * Returns `null` for absent keys — this occurs for columns not present in `pg_attribute`
   * (e.g., virtual columns, system columns with attnum <= 0).
   */
  val columnNotNullByRelidAndAttnum: Map<Pair<Int, Int>, Boolean> by lazy(::loadColumnNotNull)

  /**
   * Maps `(relid, attnum)` pairs to `true` for view columns that are guaranteed NOT NULL.
   *
   * PostgreSQL stores `pg_attribute.attnotnull = false` for all view columns. This map
   * corrects that by tracing each view column back to its source base table column via
   * `pg_depend`, then subtracting columns that are nullable due to outer joins within the
   * view's definition.
   *
   * Covers both regular views (`relkind = 'v'`) and materialized views (`relkind = 'm'`).
   * No schema filter — the node tree can reference views from any schema.
   *
   * Returns `false` for absent keys.
   */
  val viewColumnNotNullByRelidAndAttnum: Map<Pair<Int, Int>, Boolean> by lazy(::loadViewColumnNotNull)

  private fun loadColumnNotNull(): Map<Pair<Int, Int>, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      // No schema filter — relids come from the query's rtable and may reference tables
      // from any schema. Filtering by schema here would miss tables in non-default schemas.
      stmt.executeQuery(
        """
        SELECT attrelid::integer, attnum, attnotnull
        FROM pg_catalog.pg_attribute
        WHERE attnum > 0 AND NOT attisdropped
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("attrelid") to rs.getInt("attnum"), rs.getBoolean("attnotnull"))
        }
      }
    }
  }

  private fun loadViewColumnNotNull(): Map<Pair<Int, Int>, Boolean> {
    // Step 1: build the initial non-null set from pg_depend (source base table column is NOT NULL).
    val candidateNotNull = mutableMapOf<Pair<Int, Int>, Boolean>()
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          view_class.oid::integer AS view_relid,
          view_attr.attnum AS view_attnum,
          source_attr.attnotnull AS source_not_null
        FROM pg_catalog.pg_depend d
        JOIN pg_catalog.pg_rewrite rw ON rw.oid = d.objid
        JOIN pg_catalog.pg_class view_class ON view_class.oid = rw.ev_class
        JOIN pg_catalog.pg_attribute source_attr
          ON source_attr.attrelid = d.refobjid AND source_attr.attnum = d.refobjsubid
        JOIN pg_catalog.pg_class source_class ON source_class.oid = d.refobjid
        JOIN pg_catalog.pg_attribute view_attr ON view_attr.attrelid = view_class.oid
        WHERE view_class.relkind IN ('v', 'm')
          AND d.classid = 'pg_rewrite'::regclass
          AND d.refclassid = 'pg_class'::regclass
          AND d.refobjsubid > 0
          AND d.deptype = 'n'
          AND source_class.relkind IN ('r', 'p')
          AND view_attr.attnum > 0
          AND view_attr.attname = source_attr.attname
          AND source_attr.attnum > 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          val key = rs.getInt("view_relid") to rs.getInt("view_attnum")
          // Mark as non-null only when source is NOT NULL. If multiple source columns are
          // found for the same view column, keep false (safe default) if any is nullable.
          if (rs.getBoolean("source_not_null")) {
            candidateNotNull.putIfAbsent(key, true)
          } else {
            candidateNotNull[key] = false
          }
        }
      }
    }

    // Step 2: subtract columns that are nullable due to outer joins in the view's definition.
    // For each regular view (not materialized), run node tree analysis to find outer-join-nullable
    // columns by position and remove them from the non-null set.
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          c.oid::integer AS view_relid,
          rw.ev_action::text AS node_tree,
          array_agg(a.attnum ORDER BY a.attnum) AS attnums
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_rewrite rw ON rw.ev_class = c.oid AND rw.ev_type = '1'
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.relkind = 'v'
        GROUP BY c.oid, rw.ev_action
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          val viewRelid = rs.getInt("view_relid")
          val nodeTree = rs.getString("node_tree")

          @Suppress("UNCHECKED_CAST")
          val attnums = (rs.getArray("attnums").array as Array<*>).map { (it as Number).toInt() }
          val outerJoinNullable = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
          for ((index, nullable) in outerJoinNullable.withIndex()) {
            if (nullable && index < attnums.size) {
              // This view column is on the nullable side of an outer join — remove it.
              candidateNotNull[viewRelid to attnums[index]] = false
            }
          }
        }
      }
    }

    return candidateNotNull.filterValues { it }
  }

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

  private fun loadFunctionStrictness(): Map<Int, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      // Include regular functions ('f') and window functions ('w') — both appear in node tree expressions.
      // Excludes procedures ('p') and aggregates ('a') — aggregates use agginitval, not strictness.
      stmt.executeQuery(
        "SELECT oid::integer, proisstrict FROM pg_catalog.pg_proc WHERE prokind IN ('f', 'w')",
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("oid"), rs.getBoolean("proisstrict"))
        }
      }
    }
  }

  private fun loadAggregateInitialValues(): Map<Int, Boolean> = buildMap {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT aggfnoid::integer, agginitval IS NOT NULL AS has_initial_value FROM pg_catalog.pg_aggregate",
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("aggfnoid"), rs.getBoolean("has_initial_value"))
        }
      }
    }
  }

  /**
   * Returns NOT NULL column information for views and materialized views by tracing columns back to their
   * source base table columns via `pg_depend`.
   *
   * JDBC's `getColumns()` reports all view/matview columns as nullable because views carry no constraints.
   * This method resolves the actual nullability by following dependency links from view columns to base
   * table columns, where `NOT NULL` constraints exist.
   *
   * Only marks a view column as `NOT NULL` when it depends on **exactly one** base table column that is
   * `NOT NULL`. Columns backed by expressions, multiple source columns, or nullable source columns are
   * left as nullable (the safe default).
   *
   * @param schemaName The schema to check.
   * @return A set of `"viewName.columnName"` strings for view/matview columns that are non-nullable.
   */
  fun loadViewColumnNullability(schemaName: String): Set<String> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          view_class.relname AS view_name,
          view_attr.attname AS view_column,
          source_attr.attnotnull AS source_not_null
        FROM pg_catalog.pg_depend d
        JOIN pg_catalog.pg_rewrite rw ON rw.oid = d.objid
        JOIN pg_catalog.pg_class view_class ON view_class.oid = rw.ev_class
        JOIN pg_catalog.pg_namespace n ON n.oid = view_class.relnamespace
        JOIN pg_catalog.pg_attribute source_attr
          ON source_attr.attrelid = d.refobjid AND source_attr.attnum = d.refobjsubid
        JOIN pg_catalog.pg_class source_class ON source_class.oid = d.refobjid
        JOIN pg_catalog.pg_attribute view_attr ON view_attr.attrelid = view_class.oid
        WHERE n.nspname = '$schemaName'
          AND view_class.relkind IN ('v', 'm')
          AND d.classid = 'pg_rewrite'::regclass
          AND d.refclassid = 'pg_class'::regclass
          AND d.refobjsubid > 0
          AND d.deptype = 'n'
          AND source_class.relkind IN ('r', 'p')
          AND view_attr.attnum > 0
          AND view_attr.attname = source_attr.attname
          AND source_attr.attnum > 0
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          if (rs.getBoolean("source_not_null")) {
            add("${rs.getString("view_name")}.${rs.getString("view_column")}")
          }
        }
      }
    }
  }

  /**
   * Returns view columns that are nullable due to outer joins in the view's own definition.
   *
   * Reads each (non-materialized) view's query tree from `pg_rewrite.ev_action` and runs
   * [NodeTreeNullabilityAnalyzer] on it to detect which output columns can be `null` because of a
   * `LEFT JOIN`, `RIGHT JOIN`, or `FULL OUTER JOIN` inside the view. Outer-join-nullable columns
   * must remain nullable even if the underlying base table column is `NOT NULL`.
   *
   * This corrects a class of false positives from [loadViewColumnNullability]: when two joined tables
   * both have a column with the same name (e.g., `department.name` and `employee.name`), and one of
   * those tables is on the nullable side of a `LEFT JOIN`, the view column inherits `NOT NULL` from
   * the preserved side even though the actual column selected is from the nullable side. The node tree
   * analysis detects this case and vetoes the incorrect `NOT NULL`.
   *
   * Materialized views are excluded because their data is stored at refresh time; the outer-join
   * structure of the definition does not affect the persisted `NOT NULL` guarantee of a materialized
   * view's columns (PostgreSQL allows defining `NOT NULL` constraints on matview columns separately).
   *
   * @param schemaName The schema to inspect.
   * @return A set of `"viewName.columnName"` strings for view columns that are nullable from outer joins.
   */
  fun loadViewOuterJoinNullableColumns(schemaName: String): Set<String> = buildSet {
    // For each regular view, read its node tree and cross-reference with its column list.
    // The node tree's targetList positions correspond to pg_attribute attnum order.
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT
          c.relname AS view_name,
          rw.ev_action::text AS node_tree,
          array_agg(a.attname ORDER BY a.attnum) AS col_names
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_rewrite rw ON rw.ev_class = c.oid AND rw.ev_type = '1'
        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE n.nspname = '$schemaName'
          AND c.relkind = 'v'
        GROUP BY c.relname, rw.ev_action
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) {
          val viewName = rs.getString("view_name")
          val nodeTree = rs.getString("node_tree")

          @Suppress("UNCHECKED_CAST")
          val columnNames = (rs.getArray("col_names").array as Array<String>).toList()
          val outerJoinNullable = NodeTreeNullabilityAnalyzer.extractOuterJoinNullability(nodeTree)
          for ((index, nullable) in outerJoinNullable.withIndex()) {
            if (nullable && index < columnNames.size) {
              add("$viewName.${columnNames[index]}")
            }
          }
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
   * @return One [Domain] per domain type, with [Domain.base_type] as the Postgres base type name
   *   and [Domain.comment] if present.
   */
  fun introspectDomains(schemaName: String): List<Domain> = buildList {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT t.typname AS domain_name, bt.typname AS base_type, d.description
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
              base_type = rs.getString("base_type"),
              comment = rs.getString("description").orEmpty(),
            ),
          )
        }
      }
    }
  }

  /**
   * Determines which result columns of a SQL query can be NULL, using full expression evaluation.
   *
   * Creates a temporary view wrapping the query, reads the analyzed query tree from
   * `pg_rewrite.ev_action`, and evaluates each output column's expression for nullability.
   * This covers outer-join-induced nullability, aggregate return values, strict-function
   * propagation, and more.
   *
   * Parameter placeholders (`?`) are replaced with typed non-null sentinel values before view
   * creation (views cannot contain parameters). The sentinel type is determined via
   * `PreparedStatement.getParameterMetaData()`. Using non-null sentinels instead of `NULL`
   * ensures that strict functions wrapping parameters (e.g., `digest(?, ?)`) correctly evaluate
   * as non-null in the node tree. If parameter metadata is unavailable, `NULL` is used as a
   * safe fallback (the column becomes conservatively nullable).
   *
   * When the SQL contains data-modifying statements (INSERT/UPDATE/DELETE in CTEs or as the
   * outer statement), PostgreSQL rejects view creation. In that case, the SQL is transformed
   * into an equivalent SELECT that preserves the join structure:
   * - CTE bodies are replaced with `SELECT NULL::<type> AS <name>, ... WHERE FALSE` stubs
   *   whose column metadata is obtained from PostgreSQL via `PreparedStatement.getMetaData()`.
   * - `UPDATE ... FROM ... RETURNING` and `DELETE ... USING ... RETURNING` are converted to
   *   equivalent SELECT statements that preserve the FROM/USING join structure.
   * - DML without join structure (plain INSERT, UPDATE without FROM, DELETE without USING,
   *   MERGE) returns an empty list since no outer joins are possible.
   *
   * @param sql The SQL query or DML statement to analyze.
   * @return A list of booleans, one per result column in SELECT order. `true` means the
   *   column can be NULL; `false` means it is guaranteed non-null.
   */
  fun queryColumnNullability(sql: String): List<Boolean> {
    val viewSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)

    // Fast path: try creating a view directly (works for all SELECT-only SQL).
    return try {
      analyzeViaTemporaryView(viewSql)
    } catch (_: SQLException) {
      // View creation failed — SQL contains data-modifying statements.
      // Transform the SQL to remove DML while preserving join structure.
      val transformedSql = transformForViewCreation(viewSql)
        ?: return buildAllNonNullable(viewSql) // No outer joins possible → all columns non-nullable
      analyzeViaTemporaryView(transformedSql)
    }
  }

  /**
   * Replaces `?` parameter placeholders in [sql] with typed non-null sentinel values.
   *
   * Uses `PreparedStatement.getParameterMetaData()` to determine the PostgreSQL type of each
   * parameter, then builds a non-null literal of that type (e.g., `0::int4`, `''::text`).
   *
   * @return The SQL with `?` replaced by typed sentinels, or `null` if parameter metadata
   *   cannot be obtained (caller should fall back to NULL replacement).
   */
  private fun buildViewSqlWithSentinels(sql: String): String? {
    if ('?' !in sql) return sql
    return try {
      val sentinels = connection.prepareStatement(sql).use { preparedStatement ->
        val parameterMetaData = preparedStatement.parameterMetaData
        (1..parameterMetaData.parameterCount).map { index ->
          nonNullSentinel(parameterMetaData.getParameterTypeName(index))
        }
      }
      replaceParameterPlaceholdersWithSentinels(sql, sentinels)
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Creates a temporary view from [viewSql], reads its node tree from `pg_rewrite`,
   * and returns per-column nullability using full expression analysis.
   */
  private fun analyzeViaTemporaryView(viewSql: String): List<Boolean> {
    val viewName = "norm_nullability_${UUID.randomUUID().toString().replace("-", "")}"
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("CREATE TEMPORARY VIEW $viewName AS $viewSql")
      }
      val nodeTree = connection.createStatement().use { stmt ->
        stmt.executeQuery(
          "SELECT rw.ev_action::text FROM pg_rewrite rw " +
            "JOIN pg_class c ON c.oid = rw.ev_class " +
            "WHERE c.relname = '$viewName' AND c.relkind = 'v' AND rw.ev_type = '1'",
        ).use { rs ->
          check(rs.next()) { "No rewrite rule found for temporary view $viewName" }
          rs.getString(1)
        }
      }
      val rangeTable = nodeTreeParser.parseRangeTable(nodeTree) // varno → relid (base tables only)
      // GROUP BY queries use an *GROUP* RTE (rtekind 9) whose target list VARs reference the group
      // entry varno instead of the base table varno directly. Resolve these back to their base table
      // column so isSourceColumnNotNull can check pg_attribute.attnotnull correctly.
      //
      // EXCEPTION: When GROUPING SETS, CUBE, or ROLLUP is used, GROUP BY columns can receive NULL
      // for rows where the column is not part of the current grouping set. In that case, skip
      // GROUP RTE resolution so the columns are treated as nullable (safe default).
      //
      // PostgreSQL 18 enforces this via a *GROUP* RTE (rtekind 9): target list VARs reference the
      // GROUP RTE instead of the base table, so parseRangeTable() can't find them. On PostgreSQL
      // 16/17 there is no GROUP RTE — VARs reference the base table directly and would be
      // incorrectly resolved as NOT NULL. parseGroupingKeyVars() identifies those VARs so they can
      // be overridden to nullable regardless of pg_attribute.attnotnull.
      val groupingKeyVars = if (nodeTreeParser.hasGroupingSets(nodeTree)) {
        nodeTreeParser.parseGroupingKeyVars(nodeTree)
      } else {
        emptySet()
      }
      val groupRteMap = if (nodeTreeParser.hasGroupingSets(nodeTree)) {
        emptyMap()
      } else {
        nodeTreeParser.parseGroupRteMap(nodeTree) // (groupVarno, attrPos) → (baseVarno, baseVarattno)
      }
      // For subquery RTEs (rtekind 1), the outer VAR's varno is not in rangeTable.
      // Resolve their nullability by recursively analyzing each subquery's target list.
      // The map is keyed by (varno, varattno) for direct lookup in isSourceColumnNotNull.
      val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree)
      val analyzer = NodeTreeNullabilityAnalyzer(
        isStrict = { oid -> functionStrictnessByOid[oid] == true },
        hasNonNullInitialValue = { oid -> aggregateHasNonNullInitialValue[oid] == true },
        isSourceColumnNotNull = { varno, varattno ->
          if (groupingKeyVars.contains(varno to varattno)) {
            false
          } else {
            val relid = rangeTable[varno]
            if (relid != null) {
              val key = relid to varattno
              columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true
            } else {
              // Check if this is a GROUP BY RTE reference — resolve through groupexprs to the base column.
              val baseVar = groupRteMap[varno to varattno]
              if (baseVar != null) {
                val baseRelid = rangeTable[baseVar.first]
                if (baseRelid != null) {
                  val key = baseRelid to baseVar.second
                  columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true
                } else {
                  false
                }
              } else {
                // varno is not a base table or GROUP RTE — check subquery column nullability.
                subqueryColumnNotNull[varno to varattno] == true
              }
            }
          }
        },
        isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
      )
      return analyzer.extractColumnNullability(nodeTree)
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DROP VIEW IF EXISTS $viewName")
      }
    }
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for columns of subquery RTEs that are
   * guaranteed non-null.
   *
   * For each `rtekind 1` (subquery) entry in the outer query's `:rtable`, extracts the embedded
   * `:subquery {QUERY ...}` block, recursively analyzes it with a fresh [NodeTreeNullabilityAnalyzer]
   * using the **subquery's own range table**, and maps each output column position to its nullability.
   *
   * This allows the outer analyzer's [NodeTreeNullabilityAnalyzer] to correctly evaluate VARs that
   * reference a subquery derived table (`SELECT s.col FROM (...) s`) rather than a base table.
   * Without this, `isSourceColumnNotNull` for a subquery VAR would always return `false` (nullable)
   * because the subquery RTE has no `relid` in the outer range table.
   *
   * @param nodeTree the `pg_rewrite.ev_action` text of the outer query's temporary view
   * @return A map from `(varno, varattno)` pairs to `true` when the subquery column is non-null
   */
  private fun buildSubqueryColumnNotNull(nodeTree: String): Map<Pair<Int, Int>, Boolean> {
    // Set-operation queries (UNION ALL, INTERSECT, EXCEPT) store their branches as rtekind=1
    // subquery RTEs. Tracing through them would incorrectly report the first branch's nullability
    // as the result's nullability — the true result is the union across ALL branches, some of which
    // may introduce nulls (e.g., a branch with LEFT JOIN). Return empty so the analyzer conservatively
    // treats set-operation output columns as nullable (the correct safe default).
    if (nodeTreeParser.hasSetOperations(nodeTree)) return emptyMap()
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(nodeTree)
    if (subqueryRangeTable.isEmpty()) return emptyMap()
    return buildMap {
      for ((outerVarno, subqueryBlock) in subqueryRangeTable) {
        // Parse the subquery's own base-table range table for isSourceColumnNotNull.
        val subRangeTable = nodeTreeParser.parseRangeTable(subqueryBlock)
        val subAnalyzer = NodeTreeNullabilityAnalyzer(
          isStrict = { oid -> functionStrictnessByOid[oid] == true },
          hasNonNullInitialValue = { oid -> aggregateHasNonNullInitialValue[oid] == true },
          isSourceColumnNotNull = { subVarno, subVarattno ->
            val relid = subRangeTable[subVarno] ?: return@NodeTreeNullabilityAnalyzer false
            val key = relid to subVarattno
            columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true
          },
          isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
        )
        val subNullabilities = subAnalyzer.extractColumnNullability(subqueryBlock)
        subNullabilities.forEachIndexed { columnIndex, nullable ->
          // columnIndex is 0-based; varattno is 1-based
          put(outerVarno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  /**
   * Transforms SQL containing data-modifying statements into an equivalent SELECT
   * that preserves the join structure for outer join nullability analysis.
   *
   * Two transformations are applied:
   * 1. **CTE bodies** are replaced with `SELECT NULL::<type> AS <name> ... WHERE FALSE` stubs.
   *    Column metadata is obtained from PostgreSQL via `PreparedStatement.getMetaData()` on
   *    the original CTE body, so PostgreSQL does the parsing.
   * 2. **Outer DML** (`UPDATE ... FROM`, `DELETE ... USING`) is converted to an equivalent
   *    SELECT that preserves the FROM/USING join structure.
   *
   * @return The transformed SQL, or `null` if the DML has no join structure (the caller
   *   should return an empty nullability list).
   */
  private fun transformForViewCreation(sql: String): String? {
    // Phase 1: Replace CTE bodies with SELECT stubs.
    // We replace ALL CTE bodies unconditionally — the outer query's varnullingrels depend
    // on the outer join structure, not CTE internals. The stubs have matching column names
    // and types so the outer query's references resolve correctly.
    val cteClause = parseCteClause(sql)
    val result: String
    val mainQueryStart: Int
    if (cteClause != null) {
      // Build the result by concatenating segments: original text between CTE bodies stays,
      // CTE bodies are replaced with stubs. This avoids index invalidation from length changes.
      result = buildString {
        var lastEnd = 0
        for (cte in cteClause.definitions) {
          append(sql, lastEnd, cte.bodyOpenParenthesis + 1) // Include the opening '('
          val body = sql.substring(cte.bodyOpenParenthesis + 1, cte.bodyCloseParenthesis)
          append(buildSelectStub(body) ?: body) // Keep original body if stub fails
          lastEnd = cte.bodyCloseParenthesis // Will include the closing ')' next iteration
        }
        append(sql, lastEnd, sql.length)
      }
      // mainQueryStart is adjusted by the total length change from all replacements.
      mainQueryStart = cteClause.mainQueryStart + (result.length - sql.length)
    } else {
      result = sql
      mainQueryStart = 0
    }

    // Phase 2: If the outer statement is DML, convert to an equivalent SELECT.
    val mainQuery = result.substring(mainQueryStart)
    val selectEquivalent = convertDmlToSelect(mainQuery)

    return if (selectEquivalent != null) {
      result.substring(0, mainQueryStart) + selectEquivalent
    } else if (cteClause != null && cteClause.definitions.isNotEmpty()) {
      // CTEs were replaced but outer statement is SELECT (or non-transformable DML).
      // If it's SELECT, the transformed SQL should work as a view.
      // If it's INSERT/MERGE (no join structure in RETURNING), return null.
      val afterCte = skipWhitespaceAndComments(mainQuery, 0)
      if (mainQuery.regionMatches(afterCte, "SELECT", 0, 6, ignoreCase = true) ||
        mainQuery.regionMatches(afterCte, "TABLE", 0, 5, ignoreCase = true) ||
        mainQuery.regionMatches(afterCte, "VALUES", 0, 6, ignoreCase = true)
      ) {
        result
      } else {
        null // Outer DML with no join structure (INSERT, MERGE)
      }
    } else {
      null // No CTEs, outer DML with no join structure
    }
  }

  /**
   * Builds a `SELECT NULL::<type> AS <name>, ... WHERE FALSE` stub that produces the same
   * result columns as the given SQL body, using PostgreSQL's own parser to determine column
   * metadata via `PreparedStatement.getMetaData()`.
   *
   * @param body A SQL statement (the CTE body text, without surrounding parentheses).
   * @return The SELECT stub, or `null` if metadata cannot be obtained.
   */
  private fun buildSelectStub(body: String): String? {
    return try {
      connection.prepareStatement(body).use { preparedStatement ->
        val metadata = preparedStatement.metaData ?: return null
        if (metadata.columnCount == 0) return null
        buildString {
          append("SELECT ")
          for (i in 1..metadata.columnCount) {
            if (i > 1) append(", ")
            // serial/smallserial/bigserial are pseudo-types (syntactic sugar for sequence + integer).
            // JDBC returns these as type names but they can't be used in casts. Map to real types.
            val typeName = when (metadata.getColumnTypeName(i)) {
              "serial" -> "int4"
              "smallserial", "serial2" -> "int2"
              "bigserial", "serial8" -> "int8"
              else -> metadata.getColumnTypeName(i)
            }
            append("NULL::").append(typeName)
            append(" AS ").append(metadata.getColumnName(i))
          }
          append(" WHERE FALSE")
        }
      }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Returns a list of `false` values matching the result column count of [sql].
   *
   * Used when the SQL has no join structure that could produce outer-join nullability
   * (e.g., plain INSERT/DELETE/UPDATE without FROM/USING, or MERGE). The column count is
   * determined by `PreparedStatement.getMetaData()`, letting PostgreSQL parse the statement.
   */
  private fun buildAllNonNullable(sql: String): List<Boolean> {
    return try {
      connection.prepareStatement(sql).use { preparedStatement ->
        val metadata = preparedStatement.metaData ?: return emptyList()
        List(metadata.columnCount) { false }
      }
    } catch (_: SQLException) {
      emptyList()
    }
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
    val version = connection.metaData.databaseMajorVersion
    check(version >= 16) {
      "Norm requires PostgreSQL 16 or later (connected to version $version). " +
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
                  not_null = true,
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
