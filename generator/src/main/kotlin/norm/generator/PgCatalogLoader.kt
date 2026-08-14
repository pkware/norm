package norm.generator

import org.intellij.lang.annotations.Language
import java.sql.Connection
import java.sql.ResultSetMetaData
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

  private val pgMajorVersion = connection.metaData.databaseMajorVersion

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
   * OIDs of non-strict functions that are guaranteed to never return `null`, regardless of argument
   * nullability. Currently includes `concat` and `concat_ws`, which silently coerce `null` arguments
   * to empty strings.
   */
  val alwaysNonNullFunctionOids: Set<Int> by lazy(::loadAlwaysNonNullFunctions)

  /**
   * OIDs of functions that are marked STRICT in `pg_proc` but can still return `null` when all
   * arguments are non-null. JSON path-extraction operators (`->`, `->>`, `#>`, `#>>`) fall into
   * this category: they return `null` when the requested key or path does not exist in the JSON
   * value, even though all inputs are non-null.
   */
  val strictButNullableFunctionOids: Set<Int> by lazy(::loadStrictButNullableFunctions)

  /**
   * OIDs of the 3-argument overloads of `lag` and `lead` window functions. These overloads accept
   * `(value, offset, default)` and return a non-null result when both the value expression and the
   * default expression are non-null — the default fills in for rows at window boundaries where
   * 1-arg `lag`/`lead` would return `null`.
   */
  val lagLeadWithDefaultOids: Set<Int> by lazy(::loadLagLeadWithDefaultOids)

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
          source_attr.attnotnull AS source_notNull
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
          if (rs.getBoolean("source_notNull")) {
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
      // An aggregate is non-null for empty groups only when it has a non-null initial transition value
      // AND no final function. Aggregates with a finalfunc (AVG, STDDEV, etc.) can return null even
      // with a non-null agginitval because the finalfunc may produce null (e.g., AVG divides by zero
      // count).
      stmt.executeQuery(
        "SELECT aggfnoid::integer, (agginitval IS NOT NULL AND aggfinalfn = 0) AS has_initial_value FROM pg_catalog.pg_aggregate",
      ).use { rs ->
        while (rs.next()) {
          put(rs.getInt("aggfnoid"), rs.getBoolean("has_initial_value"))
        }
      }
    }
  }

  private fun loadAlwaysNonNullFunctions(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE proname IN ('concat', 'concat_ws') AND NOT proisstrict",
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadStrictButNullableFunctions(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        """
        SELECT oid::integer FROM pg_catalog.pg_proc
        WHERE proisstrict = true AND (
          proname IN (
            'jsonb_object_field', 'jsonb_object_field_text',
            'json_object_field', 'json_object_field_text',
            'jsonb_extract_path', 'jsonb_extract_path_text',
            'json_extract_path', 'json_extract_path_text',
            'jsonb_array_element', 'jsonb_array_element_text',
            'json_array_element', 'json_array_element_text'
          )
          OR (prokind = 'w' AND proname IN ('first_value', 'last_value', 'nth_value'))
          OR (prokind = 'w' AND proname IN ('lag', 'lead') AND pronargs < 3)
        )
        """.trimIndent(),
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
      }
    }
  }

  private fun loadLagLeadWithDefaultOids(): Set<Int> = buildSet {
    connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE proname IN ('lag', 'lead') AND pronargs = 3 AND prokind = 'w'",
      ).use { rs ->
        while (rs.next()) add(rs.getInt("oid"))
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
          source_attr.attnotnull AS source_notNull
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
          if (rs.getBoolean("source_notNull")) {
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
   * When the SQL contains data-modifying statements (INSERT/UPDATE/DELETE/MERGE in CTEs or as
   * the outer statement), PostgreSQL rejects view creation. In that case, the SQL is transformed
   * into an equivalent SELECT that preserves the join structure — see [transformForViewCreation]
   * for the full picture, summarized here:
   * - A data-modifying CTE body is, WHEREVER POSSIBLE, converted to an equivalent
   *   join-preserving `SELECT` (the same conversion `UPDATE ... FROM`/`DELETE ... USING`/`MERGE
   *   ... RETURNING` outer DML already uses — see [convertDmlCteBodyToSelect]) and validated
   *   against the original body's own column metadata before being trusted; only when that
   *   fails is the body replaced with a `SELECT NULL::<type> AS <name>, ... WHERE FALSE` stub
   *   built from `PreparedStatement.getMetaData()` on the original body.
   * - `UPDATE ... FROM ... RETURNING`, `DELETE ... USING ... RETURNING`, and `MERGE ...
   *   RETURNING` (outer DML, not inside a CTE) are converted to equivalent SELECT statements
   *   that preserve the join structure.
   * - For DML shapes [transformForViewCreation] cannot convert at all — plain `INSERT`, `UPDATE`
   *   without `FROM`, `DELETE` without `USING`, `MERGE` without `RETURNING`, or a top-level
   *   conversion this file could not validate — this function returns [buildAllNonNullable]'s
   *   result: EVERY result column asserted `false` (not nullable), regardless of the column's
   *   real metadata. This is NOT an empty list, and the distinction matters in the UNSAFE
   *   direction, not a merely pedantic one: `JdbcAnalyzer` treats an index missing from this list
   *   as nullable (the conservative, safe default), so an actually-empty list would defer to that
   *   safe default for every column — but the list this function actually returns here is
   *   full-length and says the OPPOSITE (`false` = NOT NULL) for every one of them. This is an
   *   ASSERTION, not a deferral, and it is wrong whenever ANY returned column is genuinely
   *   nullable — which an ordinary `DELETE`/`UPDATE ... RETURNING` of a nullable column does
   *   routinely, not rarely: no `OLD`/`NEW`, no `MERGE`, and no exotic syntax are needed to hit
   *   it, only a plain top-level statement returning a column the schema itself declares
   *   nullable. See [transformForViewCreation]'s "NOT CLAIMED" section for concrete shapes (all
   *   pre-existing on `main`) where this specific fallback fabricates NOT NULL for a column that
   *   is genuinely nullable at runtime.
   *
   * @param sql The SQL query or DML statement to analyze.
   * @return A list of booleans, one per result column in SELECT order. `true` means the
   *   column can be NULL; `false` means it is guaranteed non-null.
   */
  fun queryColumnNullability(@Language("PostgreSQL") sql: String): List<Boolean> {
    val viewSql = buildViewSqlWithSentinels(sql) ?: replaceParameterPlaceholders(sql)

    // Fast path: try creating a view directly (works for all SELECT-only SQL).
    return try {
      analyzeViaTemporaryView(viewSql)
    } catch (_: SQLException) {
      // View creation failed — SQL contains data-modifying statements.
      // Transform the SQL to remove DML while preserving join structure.
      val transformedSql = transformForViewCreation(viewSql)
        // No join structure was found (or convertible) — NOT a safe fallback: this asserts every
        // column NOT NULL (see this function's own KDoc), which is wrong whenever the statement's
        // OWN un-convertible RETURNING can genuinely be null (OLD/NEW, or a join this file failed
        // to detect or could not validate). Accepted here only because it is what this file
        // returns for every DML shape it cannot otherwise analyze — not because it is safe.
        ?: return buildAllNonNullable(viewSql)
      // transformForViewCreation validates every conversion it makes against the original SQL's
      // own column signature before returning it (see its KDoc), so this SHOULD always prepare.
      // Caught anyway, not re-thrown, as defense in depth: an `SQLException` escaping from here
      // would propagate out of queryColumnNullability entirely — nothing else on the call stack
      // catches it — turning a nullability-analysis gap into a build failure on SQL PostgreSQL
      // itself accepts fine. A future conversion bug this file's validation doesn't catch must
      // degrade to the same fallback as "no join structure found" above — an assertion, not a
      // safe deferral, but never an abort.
      try {
        analyzeViaTemporaryView(transformedSql)
      } catch (_: SQLException) {
        buildAllNonNullable(viewSql)
      }
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
      try {
        connection.createStatement().use { stmt ->
          stmt.execute("CREATE TEMPORARY VIEW $viewName AS $viewSql")
        }
      } catch (originalError: SQLException) {
        // Queries with duplicate column names (e.g., SELECT d.id, e.id FROM d JOIN e ...)
        // fail view creation. Retry with explicit column aliases to make names unique.
        val columnCount = try {
          connection.prepareStatement(viewSql).use { it.metaData?.columnCount ?: 0 }
        } catch (_: SQLException) {
          0
        }
        if (columnCount > 0) {
          val columnList = (1..columnCount).joinToString(", ") { "c$it" }
          try {
            connection.createStatement().use { stmt ->
              stmt.execute("CREATE TEMPORARY VIEW $viewName($columnList) AS $viewSql")
            }
          } catch (_: SQLException) {
            throw originalError
          }
        } else {
          throw originalError
        }
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
      val hasGroupingSets = nodeTreeParser.hasGroupingSets(nodeTree)
      val groupingKeyVars = if (hasGroupingSets) {
        nodeTreeParser.parseGroupingKeyVars(nodeTree)
      } else {
        emptySet()
      }
      val groupRteMap = if (hasGroupingSets) {
        emptyMap()
      } else {
        nodeTreeParser.parseGroupRteMap(nodeTree) // (groupVarno, attrPos) → (baseVarno, baseVarattno)
      }
      // For subquery RTEs (rtekind 1), the outer VAR's varno is not in rangeTable.
      // Resolve their nullability by recursively analyzing each subquery's target list.
      // The map is keyed by (varno, varattno) for direct lookup in isSourceColumnNotNull.
      val subqueryColumnNotNull = buildSubqueryColumnNotNull(nodeTree)
      val cteColumnNotNull = buildCteColumnNotNull(nodeTree)
      val analyzer = buildAnalyzer { varno, varattno ->
        if (groupingKeyVars.contains(varno to varattno)) {
          false
        } else {
          val relid = rangeTable[varno]
          if (relid != null) {
            isColumnNotNull(relid to varattno)
          } else {
            val baseVar = groupRteMap[varno to varattno]
            if (baseVar != null) {
              val baseRelid = rangeTable[baseVar.first]
              baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
            } else {
              subqueryColumnNotNull[varno to varattno] == true ||
                cteColumnNotNull[varno to varattno] == true
            }
          }
        }
      }
      return analyzer.extractColumnNullability(nodeTree)
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DROP VIEW IF EXISTS $viewName")
      }
    }
  }

  private fun isColumnNotNull(key: Pair<Int, Int>): Boolean =
    columnNotNullByRelidAndAttnum[key] == true || viewColumnNotNullByRelidAndAttnum[key] == true

  /**
   * Creates a [NodeTreeNullabilityAnalyzer] pre-configured with this loader's catalog lookups.
   *
   * All constructor arguments except [isSourceColumnNotNull] are identical across every call site
   * in this class. This method captures the common configuration so callers only need to supply
   * the source-column resolution strategy, which varies by context (outer query, CTE body,
   * subquery).
   */
  private fun buildAnalyzer(
    isSourceColumnNotNull: (varno: Int, varattno: Int) -> Boolean,
  ): NodeTreeNullabilityAnalyzer = NodeTreeNullabilityAnalyzer(
    isStrict = { oid -> functionStrictnessByOid[oid] == true },
    hasNonNullInitialValue = { oid -> aggregateHasNonNullInitialValue[oid] == true },
    isSourceColumnNotNull = isSourceColumnNotNull,
    isOuterJoinNullable = { nullingRelations -> nullingRelations.isNotEmpty() },
    isAlwaysNonNull = { oid -> oid in alwaysNonNullFunctionOids },
    isStrictButNullable = { oid -> oid in strictButNullableFunctionOids },
    isLagLeadWithDefault = { oid -> oid in lagLeadWithDefaultOids },
  )

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE result columns that are guaranteed
   * non-null. Analyzes each CTE body with a sub-analyzer using the CTE's own range table.
   */
  private fun buildCteColumnNotNull(nodeTree: String): Map<Pair<Int, Int>, Boolean> {
    val cteDefinitions = nodeTreeParser.parseCteList(nodeTree)
    if (cteDefinitions.isEmpty()) return emptyMap()
    val cteRteMap = nodeTreeParser.parseCteRangeTableEntries(nodeTree)
    if (cteRteMap.isEmpty()) return emptyMap()

    val resolvedCtes = mutableMapOf<String, List<Boolean>>()
    for (cte in cteDefinitions) {
      val nullabilities = analyzeCteBodyNullability(cte, resolvedCtes) ?: continue
      resolvedCtes[cte.name] = nullabilities
    }

    return buildMap {
      for ((varno, cteName) in cteRteMap) {
        val nullabilities = resolvedCtes[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  private fun analyzeCteBodyNullability(
    cte: NodeTreeCteDefinition,
    previouslyResolved: Map<String, List<Boolean>>,
  ): List<Boolean>? {
    if (nodeTreeParser.hasSetOperations(cte.queryBlock)) {
      return analyzeSetOperationBranches(cte.queryBlock, previouslyResolved, cte.name)
    }
    val analyzer = buildCteBodyAnalyzer(cte.queryBlock, previouslyResolved)
    val result = analyzer.extractColumnNullability(cte.queryBlock)
    if (result.isNotEmpty()) return result
    // DML CTEs store RETURNING columns in :returningList, not :targetList.
    val returningEntries = nodeTreeParser.parseReturningList(cte.queryBlock)
    if (returningEntries.isEmpty()) return null
    return returningEntries
      .filter { !it.isJunk }
      .sortedBy { it.resultNumber }
      .map { entry -> !analyzer.isNonNull(entry.expression) }
  }

  private fun analyzeSetOperationBranches(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
    cteName: String,
  ): List<Boolean>? {
    val subqueryRangeTable = nodeTreeParser.parseSubqueryRangeTable(queryBlock)
    if (subqueryRangeTable.isEmpty()) return null
    var seedResult: List<Boolean>? = null
    val branchResults = mutableListOf<List<Boolean>>()
    for ((_, branchBlock) in subqueryRangeTable) {
      val resolved = if (seedResult != null) {
        previouslyResolved + (cteName to seedResult)
      } else {
        previouslyResolved
      }
      val branchAnalyzer = buildCteBodyAnalyzer(branchBlock, resolved)
      val result = branchAnalyzer.extractColumnNullability(branchBlock)
      if (result.isEmpty()) continue
      branchResults.add(result)
      if (seedResult == null) seedResult = result
    }
    if (branchResults.isEmpty()) return null
    val columnCount = branchResults.maxOf { it.size }
    return (0 until columnCount).map { col ->
      branchResults.any { it.getOrElse(col) { true } }
    }
  }

  /**
   * Builds a map from `(varno, varattno)` to `true` for CTE RTE columns that are non-null,
   * based on previously resolved CTE nullabilities.
   */
  private fun buildInnerCteNotNull(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
  ): Map<Pair<Int, Int>, Boolean> {
    val innerCteRtes = nodeTreeParser.parseCteRangeTableEntries(queryBlock)
    return buildMap {
      for ((varno, cteName) in innerCteRtes) {
        val nullabilities = previouslyResolved[cteName] ?: continue
        nullabilities.forEachIndexed { columnIndex, nullable ->
          put(varno to (columnIndex + 1), !nullable)
        }
      }
    }
  }

  private fun buildCteBodyAnalyzer(
    queryBlock: String,
    previouslyResolved: Map<String, List<Boolean>>,
  ): NodeTreeNullabilityAnalyzer {
    val cteRangeTable = nodeTreeParser.parseRangeTable(queryBlock)
    val groupRteMap = nodeTreeParser.parseGroupRteMap(queryBlock)
    val innerCteNotNull = buildInnerCteNotNull(queryBlock, previouslyResolved)
    val subqueryColumnNotNull = buildSubqueryColumnNotNull(queryBlock)
    return buildAnalyzer { varno, varattno ->
      val relid = cteRangeTable[varno]
      if (relid != null) {
        isColumnNotNull(relid to varattno)
      } else {
        val baseVar = groupRteMap[varno to varattno]
        if (baseVar != null) {
          val baseRelid = cteRangeTable[baseVar.first]
          baseRelid != null && isColumnNotNull(baseRelid to baseVar.second)
        } else {
          subqueryColumnNotNull[varno to varattno] == true ||
            innerCteNotNull[varno to varattno] == true
        }
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
        val subAnalyzer = buildAnalyzer { subVarno, subVarattno ->
          val relid = subRangeTable[subVarno] ?: return@buildAnalyzer false
          isColumnNotNull(relid to subVarattno)
        }
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
   * 1. **Data-modifying CTE bodies** (a leading `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or a
   *    nested `WITH`) are converted to an equivalent join-preserving `SELECT` via
   *    [convertDmlCteBodyToSelect] wherever possible — the same transformation "Outer DML"
   *    (transformation 2, below) already uses for TOP-LEVEL DML — so the real FROM/USING/MERGE
   *    join structure sits in front of the node-tree analyzer that already computes outer-join
   *    nullability correctly, rather than approximating it. The conversion is then validated
   *    against the original body's own column metadata via [validatedConversion] before it is
   *    trusted (see its KDoc for why this check is necessary independently of how well
   *    [convertDmlCteBodyToSelect] itself is written). Only when conversion or validation fails
   *    does this fall back to a metadata PROBE — and there is more than one distinct reason that
   *    can happen, not just the shapes [convertDmlCteBodyToSelect] itself returns `null` for:
   *    - An intentional `null` from [convertDmlCteBodyToSelect]: a plain `INSERT` (whose
   *      `RETURNING` sees only the just-inserted row and has no join to preserve),
   *      `UPDATE`/`DELETE` with no `FROM`/`USING` clause, or `MERGE` with no `RETURNING` clause.
   *    - [validatedConversion] REJECTING a syntactically-built conversion that fails to prepare
   *      or doesn't reproduce the original's column signature — most notably a `RETURNING` that
   *      reads PostgreSQL 18's `OLD`/`NEW` pseudo-relations (not valid range variables outside
   *      `RETURNING`, so the converted plain `SELECT` fails to prepare) or a `MERGE`'s
   *      `merge_action()` (not valid outside `MERGE`'s own `RETURNING`, same failure mode).
   *    - A lexer limitation the scanners in `SqlUtils.kt` haven't yet been taught to handle.
   *    The stub is a `SELECT NULL::<type> AS <name> ... WHERE FALSE` built from
   *    `PreparedStatement.getMetaData()` on the original body. That probe reports base-table
   *    `attnotnull`, NOT real join-aware nullability — see [buildSelectStub]'s KDoc for exactly
   *    what it can and cannot see, and for the SAFETY NET (the `forceNullableColumn` predicate
   *    threaded through it) that now covers every danger sign this file can detect regardless of
   *    WHY conversion was rejected: a detectable outer join in an `UPDATE`/`DELETE`/`MERGE`
   *    body — but never `INSERT`, whose `RETURNING` cannot be null-extended by anything in its
   *    own source, see [isInsertBody]'s KDoc; a `RETURNING` that reads `OLD.`/`NEW.`, which
   *    applies regardless of statement kind, `INSERT` included (e.g. `INSERT ... ON CONFLICT DO
   *    UPDATE ... RETURNING OLD.col`, where `OLD` is `NULL` exactly when there was no conflict);
   *    a reference, by name, to a sibling CTE that is itself dangerous; or an `OLD`/`NEW`
   *    item-to-column mapping this file cannot confirm is reliable.
   *    **Non-data-modifying CTE bodies** (a leading `SELECT`, `TABLE`, or `VALUES`) are kept
   *    verbatim: they resolve their own references (including outer joins nested inside the
   *    body, whose induced nullability a stub built from base-table `attnotnull` cannot
   *    reproduce), and PostgreSQL only allows data-modifying statements directly under a
   *    top-level `WITH`, so a `SELECT`/`TABLE`/`VALUES` body cannot hide DML.
   * 2. **Outer DML** (`UPDATE ... FROM`, `DELETE ... USING`, `MERGE ... RETURNING`) is converted
   *    to an equivalent SELECT that preserves the join structure, via [convertDmlToSelect].
   *
   * A data-modifying CTE with no `RETURNING` clause (e.g. `INSERT ... ON CONFLICT DO NOTHING`)
   * has no result columns. PostgreSQL still rejects such a statement in `CREATE VIEW`, so its
   * body is replaced with a one-column stub (`SELECT NULL::int4 AS norm_stub WHERE FALSE`)
   * rather than being dropped entirely. PostgreSQL guarantees nothing outside the CTE can
   * reference a no-RETURNING data-modifying CTE, so the fabricated column is never read.
   *
   * @return The transformed SQL, or `null` if the DML has no join structure (or Phase 2's
   *   conversion could not be validated) — see [queryColumnNullability]'s KDoc for what the
   *   caller actually does with `null`: NOT an empty nullability list, but
   *   [buildAllNonNullable]'s full-length, every-column-`false` (asserted NOT NULL) result.
   */
  private fun transformForViewCreation(sql: String): String? {
    // Phase 1: Convert data-modifying CTE bodies to join-preserving SELECTs where possible,
    // falling back to a metadata probe/stub otherwise; keep SELECT/TABLE/VALUES bodies verbatim,
    // since their internal join structure (e.g. a LEFT JOIN inside the CTE) affects the
    // nullability of the CTE's own output columns and neither approach reproduces it as well as
    // just leaving the body alone.
    val cteClause = parseCteClause(sql)
    val result: String
    val mainQueryStart: Int
    if (cteClause != null) {
      // Build the result by concatenating segments: original text between CTE bodies stays,
      // CTE bodies are replaced with their conversion or a stub. This avoids index invalidation
      // from length changes. The full WITH clause (through the last CTE's closing ')') is one
      // candidate probe prefix for the stub fallback: it is the original statement with only the
      // main query swapped out, so every name that resolves in the original resolves in the
      // probe. Whether it is tried before or after the preceding-definitions-only prefix depends
      // on cteClause.isRecursive — see buildSelectStub's KDoc for why.
      val fullWithClausePrefix = sql.substring(0, cteClause.definitions.last().bodyCloseParenthesis + 1)
      result = buildString {
        var lastEnd = 0
        for ((index, cte) in cteClause.definitions.withIndex()) {
          append(sql, lastEnd, cte.bodyOpenParenthesis + 1) // Include the opening '('
          val body = sql.substring(cte.bodyOpenParenthesis + 1, cte.bodyCloseParenthesis)
          if (isNonDataModifyingCteBody(body)) {
            append(body) // Keep verbatim — resolves its own references, cannot hide DML.
          } else {
            // Use the ORIGINAL sql text (not the partially-stubbed builder content) as the
            // prefix, so preceding CTEs are prepared exactly as the user wrote them.
            val precedingDefinitionsPrefix = if (index > 0) {
              sql.substring(0, cteClause.definitions[index - 1].bodyCloseParenthesis + 1)
            } else {
              ""
            }
            // Order matters: it must mirror PostgreSQL's own name-visibility rules, or a probe
            // could resolve a reference to the wrong thing (e.g. a later CTE shadowing a base
            // table) and fabricate incorrect nullability instead of merely failing to prepare.
            // No bare-body ("") candidate here: for index 0, precedingDefinitionsPrefix IS the
            // empty prefix (correct — there is nothing preceding), but for index > 0 a bare-body
            // probe has known-wrong scope, so it is never tried; buildSelectStub's own
            // true-scope fallback covers the case where both precision prefixes fail.
            val prefixes = if (cteClause.isRecursive) {
              listOf(fullWithClausePrefix, precedingDefinitionsPrefix)
            } else {
              listOf(precedingDefinitionsPrefix, fullWithClausePrefix)
            }.distinct()
            // Try the structural conversion FIRST, but never trust it blindly: validate it
            // against the original body's own column metadata (count, names, types) before
            // using it, since a converted SELECT that doesn't match is worse than no conversion
            // at all — it would reach CREATE VIEW as plausible-looking but wrong SQL. See
            // validatedConversion's KDoc for why this check exists independently of correctness
            // fixes made elsewhere (e.g. the lexer fix in SqlUtils.kt, or the MERGE column-order
            // fix): defense in depth against future or undiscovered conversion bugs.
            val convertedBody = convertDmlCteBodyToSelect(body)?.let {
              validatedConversion(prefixes, body, it, fullWithClausePrefix, cte.rawName)
            }
            val stubOrBody = convertedBody ?: run {
              // Last-resort safety net for the stub fallback: force specific columns (or, for the
              // whole-body triggers, every column) nullable rather than trust base-table
              // attnotnull (which cannot see any of these dangers). See buildSelectStub's and
              // tryPrepareStub's KDoc:
              // - A RETURNING item that reads OLD./NEW. (PostgreSQL 18, including via a
              //   RETURNING WITH (OLD AS alias, ...) declared alias) has its own conditional
              //   existence no join structure is needed to explain — this applies regardless of
              //   statement kind, INSERT included (e.g. INSERT ... ON CONFLICT DO UPDATE
              //   ... RETURNING OLD.col, where OLD is NULL exactly when there was no conflict).
              //   Forced PER COLUMN — see oldOrNewReturningColumns's KDoc — but ONLY once its
              //   assumed item count is cross-checked against the real column count from this
              //   probe's own metadata: a star item (`*`, `tbl.*`) this text-only analysis failed
              //   to recognize expands to more columns than items, silently shifting every
              //   later item's real index — trusting an index without this check was itself a
              //   regression (`RETURNING (tgt.*), OLD.tval AS oldv` forced the wrong column, the
              //   second half of `tgt.*`'s expansion, leaving the real OLD-referencing column on
              //   unchecked metadata). A mismatch forces every column, same as a RECOGNIZED star.
              // - An outer join (LEFT/RIGHT/FULL JOIN at ANY nesting depth, or a MERGE with WHEN
              //   NOT MATCHED BY SOURCE) can null-extend a returned column — but NEVER for a
              //   plain INSERT, whose RETURNING sees only the just-inserted row (confirmed
              //   against real PostgreSQL: a LEFT JOIN in an INSERT's own SELECT source does not
              //   make its RETURNING columns nullable), so INSERT bodies are excluded from this
              //   trigger specifically.
              // - body referencing, BY NAME, a sibling CTE whose OWN body contains one of these
              //   same danger signs: that sibling's join structure is invisible to any scan of
              //   body's own text (see transformForViewCreation's KDoc). Scoped to siblings that
              //   are THEMSELVES dangerous — not every sibling reference — so an ordinary
              //   forward/backward reference to a plain, join-free sibling (e.g. `later AS
              //   (SELECT 'q'::TEXT AS lbl)`) does not lose its real NOT NULL columns to this
              //   safety net; only checked one level deep (the sibling's own text), not
              //   transitively through a chain of siblings referencing further siblings.
              val oldOrNewAnalysis = oldOrNewReturningColumns(body)
              val dangerousSiblingNames = cteClause.definitions
                .filter { sibling -> sibling !== cte }
                .filter { sibling ->
                  val siblingBody = sql.substring(sibling.bodyOpenParenthesis + 1, sibling.bodyCloseParenthesis)
                  hasOuterJoin(siblingBody) || hasWhenNotMatchedBySourceClause(siblingBody)
                }
                .map { it.name }
              val forceAllNullable = referencesAnyName(body, dangerousSiblingNames) ||
                (!isInsertBody(body) && (hasOuterJoin(body) || hasWhenNotMatchedBySourceClause(body)))
              val forceNullableColumn: (Int, Int) -> Boolean = { columnIndex, totalColumnCount ->
                val oldOrNewColumns = oldOrNewAnalysis.forcedColumns
                // null forcedColumns means oldOrNewReturningColumns already recognized the
                // mapping as unreliable (a recognized star coincides with an OLD/NEW reference);
                // a non-null but count-mismatched result means it DIDN'T recognize why, but the
                // real column count from this probe proves the mapping unreliable anyway — either
                // way, force every column rather than trust any specific index.
                val oldOrNewMappingUnreliable = oldOrNewColumns == null ||
                  (oldOrNewColumns.isNotEmpty() && oldOrNewAnalysis.itemCount != totalColumnCount)
                forceAllNullable || oldOrNewMappingUnreliable || oldOrNewColumns.orEmpty().contains(columnIndex)
              }
              buildSelectStub(prefixes, body, fullWithClausePrefix, cte.rawName, forceNullableColumn) ?: body
            }
            append(stubOrBody)
          }
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

    // Phase 2: If the outer statement is DML, convert to an equivalent SELECT — but, exactly like
    // a CTE body's conversion (Phase 1), only once validated against the ORIGINAL mainQuery's own
    // column signature. Without this gate, a conversion that embeds something invalid outside its
    // origin (MERGE's own RETURNING, e.g. `merge_action()`, or PostgreSQL 18's `OLD`/`NEW`) would
    // reach CREATE VIEW as plausible-looking but unpreparable SQL — and unlike a CTE body's own
    // rejection (which still has the stub/probe fallback to try), a rejected TOP-LEVEL conversion
    // simply falls through to the same "no join structure" `null` this function already returns
    // for INSERT/no-FROM/no-RETURNING shapes, which the caller turns into the all-non-nullable
    // fallback (see [queryColumnNullability]'s KDoc — that fallback ASSERTS every column NOT
    // NULL, the unsafe direction, not a safe deferral) — never a crash.
    val mainQuery = result.substring(mainQueryStart)
    val topLevelPrefix = result.substring(0, mainQueryStart)
    val selectEquivalent = convertDmlToSelect(mainQuery)?.let { converted ->
      val originalProbe = if (topLevelPrefix.isEmpty()) mainQuery else "$topLevelPrefix $mainQuery"
      tryGetColumnSignature(originalProbe)?.let { originalSignature ->
        acceptIfSignatureMatches(topLevelPrefix, converted, originalSignature)
      }
    }

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
   * Checks whether a CTE [body] is non-data-modifying — i.e. it starts with `SELECT`, `TABLE`,
   * or `VALUES` (skipping leading whitespace, comments, and any leading `(` — a body may be
   * parenthesized, e.g. `((SELECT ...))` or `(SELECT ...) UNION ALL (SELECT ...)`). Such bodies
   * must be kept verbatim rather than replaced with a stub, since PostgreSQL only allows
   * data-modifying statements directly under a top-level `WITH` — never parenthesized — so a
   * leading `(` can only wrap a `SELECT`, and a `SELECT`/`TABLE`/`VALUES` body cannot itself
   * contain `INSERT`/`UPDATE`/`DELETE`/`MERGE`. Skipping leading parens therefore cannot
   * misclassify a data-modifying body as non-data-modifying.
   *
   * A body may instead start with its OWN nested `WITH` clause (e.g. `WITH inner_cte AS (SELECT
   * 1) SELECT ...`). Text-only keyword matching cannot see past that nested clause, so this
   * strips it (via [stripLeadingNestedWithClause]) and classifies the nested clause's own main
   * statement instead — looping, since that main statement can itself start with a further
   * nested `WITH`. This is safe because PostgreSQL only permits a data-modifying statement
   * directly under a TOP-level `WITH`; a nested `WITH`'s main statement is never itself
   * data-modifying, so whatever it resolves to (`SELECT`/`TABLE`/`VALUES`, or — for a body like
   * `WITH helper AS (...) UPDATE ... RETURNING ...` — the `UPDATE`/`DELETE`/`MERGE` itself)
   * correctly determines the whole body's classification. The loop terminates when stripping
   * makes no further progress (no leading `WITH` left to strip), at which point a remaining
   * `SELECT`/`TABLE`/`VALUES` classifies as non-data-modifying and anything else (a
   * data-modifying statement, or unparseable text) classifies as data-modifying.
   *
   * @param body A SQL statement (the CTE body text, without surrounding parentheses).
   */
  private fun isNonDataModifyingCteBody(body: String): Boolean {
    var remainder = body
    while (true) {
      var position = skipWhitespaceAndComments(remainder, 0)
      while (position < remainder.length && remainder[position] == '(') {
        position = skipWhitespaceAndComments(remainder, position + 1)
      }
      if (remainder.regionMatches(position, "SELECT", 0, 6, ignoreCase = true) ||
        remainder.regionMatches(position, "TABLE", 0, 5, ignoreCase = true) ||
        remainder.regionMatches(position, "VALUES", 0, 6, ignoreCase = true)
      ) {
        return true
      }
      if (!remainder.regionMatches(position, "WITH", 0, 4, ignoreCase = true)) {
        return false
      }
      val nestedMainStatement = remainder.substring(position)
      val stripped = stripLeadingNestedWithClause(nestedMainStatement)
      if (stripped == nestedMainStatement) {
        return false
      }
      remainder = stripped
    }
  }

  /**
   * Converts a data-modifying CTE [body] into an equivalent join-preserving `SELECT`, via
   * [convertDmlToSelect] — reusing the exact transformation that already gives correct
   * outer-join nullability for TOP-LEVEL `UPDATE ... FROM`/`DELETE ... USING`/`MERGE ...
   * RETURNING` (see [transformForViewCreation]). Converting the body keeps its true join
   * structure in front of the node-tree analyzer used elsewhere in this file, instead of asking
   * `PreparedStatement.getMetaData()` a question it structurally cannot answer: base-table
   * `attnotnull` says nothing about whether an outer join null-extends that column at runtime.
   * [buildSelectStub]'s probe/stub fallback (used when this returns `null`) has exactly that
   * blind spot — see its KDoc.
   *
   * If [body] carries its OWN leading nested `WITH` clause (e.g. `WITH helper AS (...) UPDATE
   * ...`), [convertDmlToSelect] is applied to the DML statement AFTER that nested clause, and
   * the nested clause is reattached verbatim in front of the converted `SELECT`.
   * `convertDmlToSelect` only recognizes a statement starting with `UPDATE`/`DELETE`/`MERGE`, so
   * without this a nested `WITH` would make every such body look unrecognized and silently fall
   * back to the probe/stub chain instead — the exact shape the true-scope probe in
   * [buildSelectStub] exists to cover, which resolves NAMES correctly for that shape but not
   * outer-join NULLABILITY.
   *
   * @return The converted SELECT (with any nested `WITH` clause reattached), or `null` if
   *   [body] has no join structure [convertDmlToSelect] can convert — a plain `INSERT` (whose
   *   `RETURNING` sees only the just-inserted row: no join can null-extend it),
   *   `UPDATE`/`DELETE` without a `FROM`/`USING` clause, or `MERGE` without a `RETURNING`
   *   clause — or if the nested `WITH` clause itself fails to parse.
   */
  private fun convertDmlCteBodyToSelect(body: String): String? {
    val nestedCteClause = parseCteClause(body) ?: return convertDmlToSelect(body)
    val nestedWithClausePrefix = body.substring(0, nestedCteClause.definitions.last().bodyCloseParenthesis + 1)
    val innerDml = body.substring(nestedCteClause.mainQueryStart)
    return convertDmlToSelect(innerDml)?.let { "$nestedWithClausePrefix $it" }
  }

  /**
   * A CTE body or converted SELECT's result-column shape, for comparing a structural conversion
   * against the DML statement it claims to replace. Deliberately excludes nullability: the whole
   * point of [validatedConversion] is to check the conversion BEFORE trusting its nullability —
   * comparing nullability here would be circular.
   */
  private data class ColumnSignature(val names: List<String>, val typeNames: List<String>)

  /**
   * Prepares [probeSql] and, if it succeeds, captures its result columns' names and type names
   * (in order) via `PreparedStatement.getMetaData()`.
   *
   * @return The signature, or `null` if [probeSql] could not be prepared.
   */
  private fun tryGetColumnSignature(probeSql: String): ColumnSignature? = try {
    connection.prepareStatement(probeSql).use { preparedStatement ->
      val metadata = preparedStatement.metaData
      if (metadata == null) {
        ColumnSignature(emptyList(), emptyList())
      } else {
        ColumnSignature(
          names = (1..metadata.columnCount).map { metadata.getColumnName(it) },
          typeNames = (1..metadata.columnCount).map { metadata.getColumnTypeName(it) },
        )
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Confirms that [convertedBody] — [convertDmlCteBodyToSelect]'s output for [originalBody] —
   * has the SAME result columns, in the SAME order, as [originalBody] itself, before trusting it
   * enough to replace [originalBody] in the SQL sent to `CREATE VIEW`.
   *
   * This check exists independently of any particular conversion bug: string-based SQL
   * transformation (this file, and [convertDmlToSelect] in `SqlUtils.kt`) can never be proven
   * exhaustively correct for every input shape, so a converted SELECT that doesn't reproduce the
   * original's real column set — wrong count, wrong names, or wrong types — must be discarded
   * rather than reaching `CREATE VIEW` as plausible-looking but wrong SQL (silently fabricating
   * nullability, or — worse — aborting generation on SQL that PostgreSQL itself accepts fine, a
   * real regression this check specifically guards against). It is NOT sufficient on its own to
   * catch every conversion bug: if the target and source relations happen to share column names
   * and types in a way that makes a transposed order indistinguishable (e.g. both sides project
   * `id, val`), this check cannot tell a correct conversion from one with the columns in the
   * wrong order — the conversion itself must independently get column order right (see
   * `convertMergeToSelect` in `SqlUtils.kt` for exactly this case with MERGE's source/target
   * column ordering).
   *
   * [originalBody]'s signature is resolved via the same prefix chain [buildSelectStub] uses:
   * [precisionPrefixes] each as `<prefix> <originalBody>`, then — if [originalBody] carries its
   * own nested `WITH` clause, making every precision-prefix candidate a syntax error — a
   * true-scope probe (`<fullWithClausePrefix> SELECT * FROM <rawName>`). [convertedBody]'s
   * signature is resolved using THAT SAME successful prefix, wrapping it as a derived table
   * (`<prefix> SELECT * FROM (<convertedBody>) AS ...`) so it can be probed regardless of
   * whether [convertedBody] itself starts with a reattached nested `WITH` (a parenthesized
   * subquery may legally start with `WITH`; a bare statement may not have two).
   *
   * A `RETURNING OLD.col`/`NEW.col` reference (PostgreSQL 18) is one shape this catches
   * incidentally: [convertDmlToSelect] splices it into a plain `SELECT`, where `OLD`/`NEW` are
   * not valid range variables, so the converted probe fails to prepare and the conversion is
   * discarded here — falling back to the probe/stub path, which has the same known blind spot
   * to `OLD`/`NEW` (see [transformForViewCreation]'s KDoc) but at least doesn't abort outright.
   *
   * @return [convertedBody] if validated, or `null` if it could not be validated (discard the
   *   conversion and use the probe/stub fallback instead).
   */
  private fun validatedConversion(
    precisionPrefixes: List<String>,
    originalBody: String,
    convertedBody: String,
    fullWithClausePrefix: String,
    rawName: String,
  ): String? {
    for (prefix in precisionPrefixes) {
      val originalProbe = if (prefix.isEmpty()) originalBody else "$prefix $originalBody"
      val originalSignature = tryGetColumnSignature(originalProbe) ?: continue
      return acceptIfSignatureMatches(prefix, convertedBody, originalSignature)
    }
    // Every precision prefix failed to prepare the ORIGINAL body — the only way that happens is
    // a body with its own nested WITH clause (see buildSelectStub's KDoc). Resolve its real
    // signature via the same true-scope probe buildSelectStub falls back to in that case.
    val trueScopeSignature = tryGetColumnSignature("$fullWithClausePrefix SELECT * FROM $rawName") ?: return null
    return acceptIfSignatureMatches(fullWithClausePrefix, convertedBody, trueScopeSignature)
  }

  /**
   * Prepares [convertedBody] as a derived table under [prefix] and returns [convertedBody] if
   * its column signature matches [originalSignature] exactly (same names, same types, same
   * order) — or `null` if it fails to prepare, or its signature doesn't match.
   */
  private fun acceptIfSignatureMatches(
    prefix: String,
    convertedBody: String,
    originalSignature: ColumnSignature,
  ): String? {
    val convertedProbe = buildString {
      if (prefix.isNotEmpty()) append(prefix).append(' ')
      append("SELECT * FROM (").append(convertedBody).append(") AS norm_conversion_check")
    }
    val convertedSignature = tryGetColumnSignature(convertedProbe)
    return if (convertedSignature == originalSignature) convertedBody else null
  }

  /**
   * Builds a SELECT stub that produces the same result columns as the given SQL body, using
   * PostgreSQL's own parser to determine column metadata via `PreparedStatement.getMetaData()`.
   *
   * NOT NULL columns use non-null sentinels (e.g., `0::int4`) so that CTE body analysis correctly
   * identifies them as non-null. Nullable columns use `NULL::type` so they evaluate as nullable.
   * The `WHERE FALSE` ensures no rows are returned (the stub is for type/nullability metadata only).
   *
   * FUNDAMENTAL LIMITATION — read before adding a new caller: `ResultSetMetaData.isNullable` is
   * PostgreSQL's ORIGIN tracking for the projected column (ultimately, base-table
   * `pg_attribute.attnotnull`). It has no idea whether an outer join upstream of that column can
   * null-extend it at runtime, nor whether the column is one of PostgreSQL 18's `OLD`/`NEW`
   * pseudo-relations, whose own existence is conditional. A column that is genuinely `NOT NULL`
   * in its source table, but is read through a `LEFT JOIN` that produced no matching row, is
   * reported here as NOT NULL — this is unsafe, a real fabricated-NOT-NULL defect, not a
   * theoretical one (confirmed against running PostgreSQL: `UPDATE t SET ... FROM a LEFT JOIN b
   * ON ... RETURNING b.val` inside a CTE reported `NOT NULL` for a column real PostgreSQL
   * returns `NULL` for). This function is intended to be reached ONLY when `body` provably has
   * no danger of either kind: [transformForViewCreation] tries [convertDmlCteBodyToSelect] first
   * (which converts every body that HAS a `FROM`/`USING`/`MERGE` join into a real `SELECT`
   * handled by the join-aware node-tree analyzer instead) and validates that conversion via
   * [validatedConversion] before trusting it — see [transformForViewCreation]'s KDoc for the
   * full enumeration of reasons conversion or validation can fail (INSERT/no-FROM/no-RETURNING
   * shapes, a rejected `OLD`/`NEW`/`merge_action()` conversion, or a lexer limitation). That
   * intent is enforced by the CALLERS' current logic, not by anything provable about arbitrary
   * input — a future conversion bug, or a body shape neither the converter nor the validator
   * recognizes, could still land a genuinely dangerous body here.
   *
   * As a LAST-RESORT SAFETY NET for exactly that "could still land here" case, the caller builds
   * [forceNullableColumn] (see [tryPrepareStub]'s parameter KDoc) to force specific columns
   * nullable — or, for the whole-body danger signs below, every column — whenever a danger sign
   * is detected in `body`. Whole-body over-approximation is deliberate for these: identifying
   * precisely which columns a join affects isn't possible from metadata alone (see
   * [tryPrepareStub]'s KDoc):
   * - A detectable outer join (`LEFT`/`RIGHT`/`FULL JOIN` at ANY nesting depth, or a `MERGE` with
   *   `WHEN NOT MATCHED BY SOURCE`) — but NEVER for a plain `INSERT`: confirmed against running
   *   PostgreSQL, `INSERT INTO b(id, bval) SELECT a.id, 'v' FROM a LEFT JOIN b2 ON b2.id = a.id
   *   RETURNING id, bval` returns non-null values for both columns despite the `LEFT JOIN` in
   *   its own source, because an `INSERT`'s `RETURNING` can only ever see the row that was
   *   (or wasn't) actually inserted — see [isInsertBody]'s KDoc. Forcing nullability here for an
   *   `INSERT` was ITSELF a real over-nullability regression this exclusion fixes: it broke
   *   generated code that legitimately relied on a non-null type.
   * - `body` referencing, BY NAME, another CTE in the same `WITH` clause (a sibling) whose OWN
   *   body contains a detectable outer join or `WHEN NOT MATCHED BY SOURCE`: that danger sign can
   *   null-extend a column this body's `RETURNING` merely passes through, and no scan of `body`'s
   *   own text can see it — the null-extension lives entirely outside it. Confirmed against
   *   running PostgreSQL: `WITH pre AS (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id), m
   *   AS (MERGE INTO tgt USING pre ON pre.id = tgt.id WHEN MATCHED THEN UPDATE SET tval = 'z'
   *   RETURNING merge_action(), pre.bval, tgt.id) SELECT * FROM m` returns `bval = NULL` for a
   *   target row whose matching `pre` row came from `b`'s nullable side of the join — invisible
   *   to `m`'s own text, which has no join at all. This trigger checks ONLY the referenced
   *   sibling's own body text, one level deep — a sibling that is itself only dangerous because
   *   IT references a further, join-containing sibling is not currently followed transitively
   *   (see NOT CLAIMED below). Scoping this to siblings that are themselves dangerous — rather
   *   than firing for ANY sibling reference at all — matters: an ordinary forward reference to a
   *   plain, join-free sibling (e.g. `later AS (SELECT 'q'::TEXT AS lbl)`, used purely to supply a
   *   literal value) must not lose its own real NOT NULL columns to this safety net.
   *
   * A `RETURNING` that reads `OLD.`/`NEW.` (PostgreSQL 18) — this one DOES apply to `INSERT` too
   * (e.g. `INSERT ... ON CONFLICT DO UPDATE ... RETURNING OLD.col`, where `OLD` is `NULL` exactly
   * when there was no conflict), independently of whether any join is present — is instead forced
   * PER COLUMN: see [oldOrNewReturningColumns]'s KDoc for why whole-body forcing here was itself
   * a regression (`RETURNING OLD.name, t.id` fabricated `t.id` nullable despite `t.id` never
   * being touched by `OLD`/`NEW` at all).
   *
   * RESIDUAL IMPRECISION, accepted rather than solved: for `MERGE`, the whole-body triggers still
   * demote TARGET columns that are actually always present (e.g. the target's primary key)
   * whenever a `MERGE`'s conversion is rejected AND one of them fires — the stub has no way to
   * isolate which returned columns come from the join's/sibling's null-extended side from which
   * come from the always-present target row. Per-column isolation was considered and rejected
   * (twice) for the outer-join case specifically: `ResultSetMetaData.getTableName` reports the
   * base RELATION, not the alias used in the query, so a self-join makes the join side and the
   * target side indistinguishable by name alone anyway. Over-nullable is the safe direction, so
   * this is left as-is.
   *
   * NOT CLAIMED: a body whose null-extension originates entirely outside it — in a base table's
   * own view definition, a function it calls, a sibling CTE that is dangerous only TRANSITIVELY
   * (through a further sibling this one-level-deep check does not follow), or anything else this
   * file cannot see — is not caught by any of the above and may still be reported NOT NULL.
   * Nothing in this file claims to enumerate every way nullability can leak into a body from
   * outside its own text — only the shapes verified against real PostgreSQL and listed here.
   *
   * Two further pre-existing gaps, confirmed against real PostgreSQL, are not fixed here (both
   * predate this file's sibling-CTE and per-column work, and are out of this change's scope):
   * - The sibling-CTE trigger only matches an UNQUOTED (or aliased, or subquery-nested) reference
   *   to the dangerous sibling's name: [referencesAnyName] runs through [findKeywordAtAnyDepth],
   *   which SKIPS OVER double-quoted identifiers as an opaque lexical token (by design — see
   *   [skipLexicalToken]'s KDoc — a quoted identifier's contents are never themselves scanned for
   *   keywords), so `MERGE INTO tgt USING "pre" ON "pre".id = tgt.id ... RETURNING
   *   merge_action(), "pre".bval, tgt.id` — quoting a plain, unremarkable lowercase name that
   *   didn't need quoting at all — never fires the trigger, fabricating `bval` NOT NULL despite
   *   `pre` containing exactly the same dangerous `LEFT JOIN` as the working, unquoted example
   *   above.
   * - A body's OWN null-extension is only detected via [hasOuterJoin] or
   *   [hasWhenNotMatchedBySourceClause] — an outer join or MERGE's own not-matched-by-source
   *   branch. Other constructs that make a MERGE's matched source row conditionally absent-valued
   *   are not recognized at all: `MERGE INTO tgt USING (SELECT id, count(*) AS c FROM a WHERE
   *   id = 1 GROUP BY ROLLUP(id)) s ON tgt.id = COALESCE(s.id, 2) WHEN MATCHED THEN UPDATE SET
   *   tval = 'z' RETURNING merge_action(), s.id AS sid, tgt.id` fabricates `sid` NOT NULL — the
   *   `ROLLUP` supertotal row has `s.id = NULL` by definition, matched here only via the
   *   `COALESCE` in the `ON` condition, with no `LEFT`/`RIGHT`/`FULL JOIN` keyword or `WHEN NOT
   *   MATCHED BY SOURCE` clause anywhere in the body for either detector to find.
   *
   * EVERYTHING ABOVE IN THIS KDOC IS SCOPED TO THIS FUNCTION'S CALLERS — the CTE-body path (Phase
   * 1 of `transformForViewCreation`). The TOP-LEVEL (non-CTE) DML path (Phase 2) has NO equivalent
   * safety net at all: this function, [oldOrNewReturningColumns], [referencesAnyName], and
   * [hasOuterJoin] are never consulted for a bare top-level statement — there is no stub path for
   * top-level DML to fall back to, only `queryColumnNullability`'s `buildAllNonNullable` fallback,
   * which asserts EVERY column NOT NULL, unconditionally, whenever Phase 2's conversion attempt
   * fails or is rejected. [hasWhenNotMatchedBySourceClause] is DIFFERENT: it IS consulted for a
   * top-level `MERGE`, via `convertMergeToSelect` (called from `convertDmlToSelect`, which Phase 2
   * calls directly) — not as a stub-path trigger, but as part of the conversion itself, to choose
   * `RIGHT JOIN` over a plain `JOIN` when modeling the `MERGE`'s shape. When that conversion
   * succeeds and validates, a top-level `WHEN NOT MATCHED BY SOURCE` `MERGE` is analyzed
   * ACCURATELY by the join-aware node-tree analyzer, not approximated — confirmed against real
   * PostgreSQL: this is a case HEAD fixes relative to `main` (which never attempted `MERGE`
   * conversion at all), flipping a fabricated NOT NULL to the correct nullable.
   *
   * The residual top-level gap, then, is specifically: `OLD`/`NEW` references, `merge_action()`,
   * and any other shape that forces Phase 2's conversion to be rejected (or never attempted, e.g.
   * plain `INSERT`) — landing on `buildAllNonNullable` with no join-awareness of its own. Five
   * shapes confirmed against real PostgreSQL to fabricate NOT NULL this way, all pre-existing on
   * `main` (this function's CTE-path protections do not apply to any of them, since none involve
   * a CTE):
   * - `MERGE INTO tgt USING a ON a.id = tgt.id WHEN NOT MATCHED THEN INSERT (id, tval) VALUES
   *   (a.id, a.aval) RETURNING OLD.tval AS oldv` — a freshly `INSERT`ed row via `MERGE` has no
   *   prior row, so `OLD.tval` is genuinely `NULL`; reported NOT NULL.
   * - `MERGE INTO tgt USING a ON a.id = tgt.id WHEN MATCHED THEN DELETE RETURNING NEW.tval` — a
   *   deleted row has no resulting row, so `NEW.tval` is genuinely `NULL`; reported NOT NULL.
   * - `INSERT INTO tgt VALUES (99, 'x') ON CONFLICT (id) DO UPDATE SET tval = 'y' RETURNING
   *   OLD.tval` (top-level, no CTE) — a fresh insert (no conflict) has no prior row; reported NOT
   *   NULL.
   * - `MERGE INTO tgt USING (SELECT a.id, b.bval FROM a LEFT JOIN b ON b.id = a.id) s ON s.id =
   *   tgt.id WHEN MATCHED THEN UPDATE SET tval = 'z' RETURNING merge_action() AS act, s.bval` —
   *   `b`'s `LEFT JOIN` genuinely null-extends `s.bval`; reported NOT NULL only because
   *   `merge_action()` forces the conversion to be rejected — the SAME query WITHOUT
   *   `merge_action()` in `RETURNING` converts successfully and is correctly reported nullable,
   *   confirming the outer join itself is not the obstacle, only the rejected-conversion fallback
   *   having no join-awareness of its own.
   * - The everyday, no-`OLD`/`NEW`/`MERGE` case: a plain `DELETE FROM t WHERE id = ? RETURNING id,
   *   name, note` or `UPDATE ... RETURNING ..., docn`, where `note`/`docn` is a genuinely nullable
   *   column, reports it NOT NULL — a plain `DELETE`/`UPDATE` with no `FROM`/`USING` clause has no
   *   join structure for [convertDmlToSelect] to convert in the first place (see its KDoc), so it
   *   goes straight to `buildAllNonNullable`. That function DOES prepare the statement and DOES
   *   get real `ResultSetMetaData` back — the same object `tryPrepareStub` reads
   *   `isNullable(i)` from — but discards it, hardcoding every column `false` instead of
   *   consulting it. The SAME statement, wrapped in a CTE (`WITH d AS (DELETE ... RETURNING id,
   *   name, note) SELECT * FROM d`), is reported correctly, because the CTE-body stub path
   *   (Phase 1) reads that same metadata's `isNullable(i)` rather than ignoring it. This is the
   *   single most likely of these five shapes to affect a real user: it requires no `MERGE`, no
   *   PostgreSQL 18, and no CTE avoidance — just an ordinary top-level `DELETE`/`UPDATE ...
   *   RETURNING` of a nullable column.
   *
   * [precisionPrefixes] are tried first, in order, each as `prefix + " " + body` (or `body`
   * alone for an empty prefix — correct precisely when `body` is the first CTE, since then there
   * is nothing preceding to include). This is needed because `body` may reference another CTE in
   * the same `WITH` clause by name — including, under `WITH RECURSIVE`, one declared LATER in
   * the clause — and a prefix that omits that CTE fails to prepare. A prefix that fails to
   * prepare (e.g. it references a name not yet in scope) throws `SQLException`, which is caught
   * so the next candidate is tried; a prefix that prepares successfully but yields no result
   * columns is a SUCCESS (see below), not a failure.
   *
   * Order among [precisionPrefixes] is significant, not just a performance tuning choice: it
   * must mirror which names PostgreSQL actually makes visible to `body` in the real
   * (untransformed) statement, or a probe could silently resolve a reference to the wrong thing.
   * The full `WITH` clause prefix resolves every name visible under `WITH RECURSIVE`, including
   * forward references — but under a plain `WITH`, it also makes visible CTEs declared AFTER
   * `body` that the real statement does NOT see. If one of those later CTEs happens to share a
   * name with a base table or outer reference `body` uses, trying the full clause first would
   * resolve that reference to the later CTE instead — preparing successfully, but against the
   * wrong table, fabricating incorrect nullability rather than merely failing. Callers therefore
   * order [precisionPrefixes] to match `cteClause.isRecursive`: preceding-definitions-only first
   * for a plain `WITH`, full clause first for `WITH RECURSIVE` (where it is the only prefix that
   * can resolve a genuine forward reference).
   *
   * If `body` itself carries a nested `WITH` (e.g. `ins AS (WITH helper AS (...) INSERT ...
   * RETURNING ...)`), EVERY [precisionPrefixes] candidate is a syntax error — `<prefix> <body>`
   * becomes `WITH ... WITH helper AS (...) INSERT ...`, and PostgreSQL does not allow a second
   * top-level `WITH`. Splicing `body`'s nested `WITH` into the prefix instead of probing it as a
   * body was considered and rejected: promoting a body's own `WITH RECURSIVE` into the prefix
   * would change a plain sibling's semantics (e.g. a sibling `src AS (SELECT ... FROM src)`
   * referencing the base table `src` would become a recursive self-reference). Instead, once
   * every [precisionPrefixes] candidate fails, this falls back to a TRUE-SCOPE probe: [rawName]
   * is looked up through the unmodified [fullWithClausePrefix] — `<fullWithClausePrefix> SELECT
   * * FROM <rawName>` — keeping `body`'s own nested `WITH` exactly where PostgreSQL expects it.
   * Data-modifying CTEs are legal there (only `CREATE VIEW` rejects them), `getMetaData()` is
   * Describe-only so nothing executes (the same reliance the precision prefixes already have on
   * unmodified sibling DML bodies), and name resolution is byte-identical to the real statement
   * — including nested `WITH`, `RECURSIVE`, shadowing, and quoted names ([rawName] preserves the
   * user's exact quoting, unlike the quote-stripped `CteDefinition.name`). PostgreSQL's
   * target-list origin tracking traces a bare column reference back through `SELECT * FROM
   * <cte>` to its ultimate source column (so `ResultSetMetaData.isNullable`/`getTableName` is
   * often exact even here, and the tracing itself can also lose the thread when an expression
   * breaks the chain — PostgreSQL then reports nullability as "unknown", which the check below
   * treats as nullable). Neither of those is the real risk of this fallback, though: see the
   * FUNDAMENTAL LIMITATION note at the top of this KDoc — origin tracking is blind to
   * outer-join null extension regardless of whether it successfully traces to a source column,
   * so this probe is INTENDED to be reached only for `body` shapes with no join to be blind to
   * — a guarantee enforced by the callers' current logic, not one this function can verify about
   * its own input.
   *
   * If `body` has no `RETURNING` clause at all, `SELECT * FROM <rawName>` itself fails to
   * prepare (PostgreSQL: no `RETURNING` clause on that CTE), which is indistinguishable here
   * from the `WITH` clause being unsound for some other reason — so a further probe,
   * `<fullWithClausePrefix> SELECT 1`, checks the clause alone (with a trivial main query that
   * doesn't reference [rawName] at all). If that prepares, the clause is fine and `body` is
   * simply unreferenceable, so the standard no-`RETURNING` stub (see below) is returned; if it
   * doesn't, every avenue has failed and `null` is returned as before.
   *
   * If preparing succeeds but the statement has no result columns (a data-modifying CTE with no
   * `RETURNING` clause, e.g. `INSERT ... ON CONFLICT DO NOTHING`), a single fabricated column
   * (`SELECT NULL::int4 AS norm_stub WHERE FALSE` — nullable, since the literal is `NULL`) is
   * returned instead of `null`. PostgreSQL rejects any reference to a no-`RETURNING`
   * data-modifying CTE, so this column is provably unreferenced by the rest of the query — it
   * exists only so the CTE body is a valid, DML-free `SELECT` that `CREATE VIEW` will accept.
   *
   * FORCED NULLABILITY: [forceNullableColumn] is consulted for every 1-based result column index
   * (together with the probe's total column count); wherever it returns `true`, that column is
   * built as `NULL::<type>` regardless of what `ResultSetMetaData.isNullable` reports — see
   * [tryPrepareStub]'s parameter KDoc for why callers force specific columns (an `OLD`/`NEW`
   * reference — see [oldOrNewReturningColumns]) or every column (an outer join, a reference to a
   * sibling CTE, or an unreliable `OLD`/`NEW` item-to-column mapping) this way.
   *
   * @param precisionPrefixes Candidate prefixes to try first, in order, as original SQL text
   *   ending in `)`.
   * @param body A SQL statement (the CTE body text, without surrounding parentheses).
   * @param fullWithClausePrefix The entire original `WITH` clause (through the last CTE's
   *   closing `)`), used for the true-scope fallback probes.
   * @param rawName The CTE's name exactly as written in the original SQL (quotes included, if
   *   any), used verbatim in the true-scope fallback probe's `FROM` clause.
   * @param forceNullableColumn See FORCED NULLABILITY above and [tryPrepareStub]'s KDoc.
   * @return The SELECT stub, or `null` if the statement could not be prepared by any means.
   */
  private fun buildSelectStub(
    precisionPrefixes: List<String>,
    body: String,
    fullWithClausePrefix: String,
    rawName: String,
    forceNullableColumn: (columnIndex: Int, totalColumnCount: Int) -> Boolean,
  ): String? {
    for (prefix in precisionPrefixes) {
      val probeSql = if (prefix.isEmpty()) body else "$prefix $body"
      val stub = tryPrepareStub(probeSql, forceNullableColumn)
      if (stub != null) return stub
    }
    tryPrepareStub("$fullWithClausePrefix SELECT * FROM $rawName", forceNullableColumn)?.let { return it }
    val withClauseAloneIsSound = try {
      connection.prepareStatement("$fullWithClausePrefix SELECT 1").use { it.metaData }
      true
    } catch (_: SQLException) {
      false
    }
    return if (withClauseAloneIsSound) "SELECT NULL::int4 AS norm_stub WHERE FALSE" else null
  }

  /**
   * Prepares [probeSql] and, if it succeeds, builds a SELECT stub from its result column
   * metadata (or the standard no-`RETURNING` stub if it has no result columns).
   *
   * @param forceNullableColumn Given a 1-based result column index and the probe's total column
   *   count, `true` if that column should be built as `NULL::<type>` regardless of
   *   `ResultSetMetaData.isNullable`. The caller has determined that `body` might contain an
   *   outer join (`LEFT`/`RIGHT`/`FULL JOIN`, or a `MERGE` with `WHEN NOT MATCHED BY SOURCE`),
   *   reference a sibling CTE by name, have a `RETURNING` item that reads `OLD`/`NEW`, or have an
   *   `OLD`/`NEW` item-to-column mapping this file cannot confirm is reliable (an unrecognized
   *   star item shifting every later item's real index — see [oldOrNewReturningColumns]'s KDoc,
   *   which is why the total column count is passed here at all: callers cross-check it against
   *   their own assumed item count) — and base-table `attnotnull` (which is all `isNullable` can
   *   ever report — see this function's caller's KDoc, "FUNDAMENTAL LIMITATION") cannot see any
   *   of those. For the outer-join, sibling-CTE, and unreliable-mapping triggers, callers pass a
   *   lambda returning `true` for every column rather than trying to identify which specific ones
   *   are affected: `ResultSetMetaData.getTableName` reports the base RELATION, not the alias used
   *   in the query, so a self-join (`FROM t LEFT JOIN t2 ...` where `t2` is another instance of
   *   the SAME table as the target) makes the join side and the always-present target side
   *   indistinguishable by relation name alone. This also confines the over-nullability to
   *   exactly the bodies that already reached this fallback (conversion unavailable or
   *   rejected) — a body that converts successfully still gets exact nullability from the
   *   join-aware node-tree analyzer, never this approximation.
   * @return The stub, or `null` if [probeSql] could not be prepared.
   */
  private fun tryPrepareStub(
    probeSql: String,
    forceNullableColumn: (columnIndex: Int, totalColumnCount: Int) -> Boolean,
  ): String? = try {
    connection.prepareStatement(probeSql).use { preparedStatement ->
      val metadata = preparedStatement.metaData
      if (metadata == null || metadata.columnCount == 0) {
        "SELECT NULL::int4 AS norm_stub WHERE FALSE"
      } else {
        buildString {
          append("SELECT ")
          for (i in 1..metadata.columnCount) {
            if (i > 1) append(", ")
            val typeName = when (metadata.getColumnTypeName(i)) {
              "serial" -> "int4"
              "smallserial", "serial2" -> "int2"
              "bigserial", "serial8" -> "int8"
              else -> metadata.getColumnTypeName(i)
            }
            val isNullable = forceNullableColumn(i, metadata.columnCount) ||
              metadata.isNullable(i) != ResultSetMetaData.columnNoNulls
            if (isNullable) {
              append("NULL::").append(typeName)
            } else {
              append(nonNullSentinel(typeName))
            }
            append(" AS ").append(quoteStubColumnAlias(metadata.getColumnName(i)))
          }
          append(" WHERE FALSE")
        }
      }
    }
  } catch (_: SQLException) {
    null
  }

  /**
   * Double-quotes [name] for use as a stub column alias, doubling any embedded `"`.
   *
   * `ResultSetMetaData.getColumnName` reports the `RETURNING` alias exactly as PostgreSQL
   * resolved it — case-exact, and not necessarily a valid bare identifier at all (e.g.
   * `?column?` for a nameless item like `RETURNING 1`). Emitting it unquoted would let
   * PostgreSQL fold any mixed-case name to lowercase, so a quoted, mixed-case original alias
   * (e.g. `RETURNING id AS "myId"`) would silently become `myid` on the stub built here — and
   * the outer query's `"myId"`-quoted reference to the CTE would then fail to resolve against
   * it (`column ins.myId does not exist`) when this stub is used to create the temporary view
   * [analyzeViaTemporaryView] reads nullability from. That failure never escapes as a build
   * error: [queryColumnNullability] catches it around its own [analyzeViaTemporaryView] calls
   * (see its inline "never an abort" note) and silently degrades to [buildAllNonNullable],
   * which asserts EVERY column of the CTE NOT NULL regardless of truth — so the CTE's real
   * nullability is lost, and a genuinely nullable `RETURNING` column is wrongly reported NOT
   * NULL. This stub is throwaway SQL used only to
   * probe nullability, so quoting unconditionally (unlike
   * `JdbcAnalyzer.buildIdentifierQuoter`'s conditional quoting, which matters for generated,
   * user-facing SQL) is always valid and also handles names like `?column?` that aren't valid
   * bare identifiers to begin with.
   */
  private fun quoteStubColumnAlias(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

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
