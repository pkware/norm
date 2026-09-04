package norm.generator

/**
 * Typed representation of PostgreSQL expression nodes from `pg_node_tree` text.
 *
 * Each subtype carries only the fields needed for nullability analysis, not a full PG node model.
 * Unrecognized node types are represented as [Unknown], which evaluates to nullable (safe default).
 */
internal sealed interface PgNodeExpression {

  /**
   * @property levelsUp `0` when this `Var` resolves in the current query level. A value greater
   *   than `0` means it is an outer reference from a correlated or LATERAL subquery, and [varno]
   *   refers to an enclosing query's range table rather than the current block's, so it must not
   *   be interpreted against the current block.
   * @property returningType `0` for an ordinary `Var` (including every `Var` on PostgreSQL versions
   *   older than 18, which never emit `:varreturningtype` at all). `1` means this `Var` reads the
   *   `OLD` row of a PostgreSQL 18+ `RETURNING WITH (OLD AS o, NEW AS n)` clause; `2` means `NEW`.
   *   An `OLD`-row `Var` is byte-identical to its `NEW` counterpart except for this field — in
   *   particular, [nullingRelations] is empty for both, because "the matched row does not exist for
   *   this `MERGE` action" is not an outer-join null-extension the planner tracks that way. A `1`
   *   here must therefore be treated as unconditionally nullable regardless of [nullingRelations]
   *   or the source column's own `NOT NULL` constraint: whether a `MERGE`'s `WHEN NOT MATCHED THEN
   *   INSERT` action fired for a given result row (leaving no `OLD` row to read) is not something
   *   this analyzer proves one way or the other, and "cannot be proven non-null" must resolve to
   *   nullable, never to a confidently wrong `NOT NULL`.
   */
  data class Var(
    val varno: Int,
    val varattno: Int,
    val nullingRelations: Set<Int>,
    val levelsUp: Int = 0,
    val returningType: Int = 0,
  ) : PgNodeExpression

  data class Const(val isNull: Boolean) : PgNodeExpression

  /**
   * @property isVariadic `true` when the call uses `VARIADIC` (e.g. `concat(VARIADIC arr)`) —
   *   from `:funcvariadic`. In this form the last entry of [arguments] is the array expression
   *   itself, passed through as one value, not exploded into its elements. This matters for
   *   nullability: `concat(VARIADIC arr)` is `null` when `arr` itself is `null` on PostgreSQL
   *   16, 17, and 18, which neither [PgCatalogLoader.alwaysNonNullFunctionOids] nor
   *   [PgCatalogLoader.nonNullIffFirstArgumentNonNullFunctionOids] account for on their own — both
   *   assume the ordinary (non-`VARIADIC`) calling form where every argument is an individual
   *   scalar value, and [NodeTreeNullabilityAnalyzer.isNonNull] must check this flag before
   *   trusting either list unconditionally.
   */
  data class FuncExpr(val functionOid: Int, val arguments: List<PgNodeExpression>, val isVariadic: Boolean = false) :
    PgNodeExpression

  data class OpExpr(val operatorFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  /**
   * @property useOr `true` for `ANY`/`SOME`, `false` for `ALL`.
   */
  data class ScalarArrayOpExpr(
    val operatorFunctionOid: Int,
    val arguments: List<PgNodeExpression>,
    val useOr: Boolean = false,
  ) : PgNodeExpression

  data class CoalesceExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class NullIfExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class MinMaxExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class Aggref(val aggregateFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class WindowFunc(val windowFunctionOid: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  /**
   * @property outerOperand The parsed first argument of the sublink's `:testexpr` (e.g. `a` in `a =
   *   ANY (...)`), `null` when `:testexpr` is absent — `EXISTS`/`EXPR`/`MULTIEXPR`/`ARRAY`/`CTE`
   *   sublinks emit none — or when it has no single-argument `:args` list this parser can read, which
   *   is true of every multi-column row-comparison form (the `BOOLEXPR` shape has no top-level
   *   `:args`; the `ROWCOMPAREEXPR` shape uses `:largs`/`:rargs` and parses to [Unknown] anyway).
   *   Extracted regardless of `subLinkType` — see [PgNodeTreeParser.parseSubLink].
   * @property subselectBlock The raw `{QUERY ...}` text of the sublink's `:subselect`, verbatim,
   *   `null` when absent (malformed input, or a node-tree shape this parser does not model). Used
   *   by [NodeTreeNullabilityAnalyzer] to recursively analyze an `ANY_SUBLINK`'s (`IN`/`= ANY`) or
   *   `ALL_SUBLINK`'s (`x op ALL (...)`) subquery body for the "exactly one non-null output column"
   *   leg of its nullability rule — see that class's `SubLink` branch. Deliberately kept as raw text
   *   rather than a parsed [PgNodeExpression] (that type only models scalar expressions, never a
   *   whole query block), so analyzing it requires re-entering [PgNodeTreeParser]/
   *   `ColumnNullabilityAnalyzer`, not this class's own `when` dispatch.
   * @property testExpressionOperatorOid The `:opfuncid` of the sublink's `:testexpr` when that
   *   `:testexpr` is a single top-level `OPEXPR` (`a = ANY (...)`, `a IN (...)`, `a <> ALL (...)`),
   *   `null` otherwise. Used alongside [outerOperand] by [NodeTreeNullabilityAnalyzer] to confirm the
   *   comparison operator is both strict and total on non-null input before trusting the subquery
   *   column's own nullability; `null` means that proof is unavailable and the sublink defaults to
   *   nullable. That naturally covers every multi-column form without special-casing: neither the
   *   `BOOLEXPR` testexpr of `(a, b) IN (...)`/`<> ALL`/`= ALL` nor the `ROWCOMPAREEXPR` testexpr of
   *   `(a, b) < ALL (...)` (and `<=`/`>`/`>=`) yields a top-level `:opfuncid`.
   */
  data class SubLink(
    val subLinkType: Int,
    val outerOperand: PgNodeExpression? = null,
    val subselectBlock: String? = null,
    val testExpressionOperatorOid: Int? = null,
  ) : PgNodeExpression

  /**
   * @property testExpression The `CASE testexpr WHEN ...` test expression (from `:arg`), `null`
   *   for the `CASE WHEN cond THEN ...` form, which has no single test expression. Not evaluated
   *   by [NodeTreeNullabilityAnalyzer.isNonNull] — a `CASE`'s own nullability never depends on its
   *   test expression's nullability — but retained so a `Var` that appears only here (e.g.
   *   `CASE a WHEN 'x' THEN 1 ELSE 2 END`) is reachable by
   *   [NodeTreeNullabilityAnalyzer] for GROUPING SETS/CUBE/ROLLUP Var-reachability analysis.
   * @property whenConditions The condition expression of each `WHEN` arm (from each `CASEWHEN`
   *   block's `:expr`). Like [testExpression], not evaluated by [NodeTreeNullabilityAnalyzer.isNonNull]
   *   — a `WHEN` condition's nullability does not affect the `CASE` result's nullability — but
   *   retained for the same Var-reachability reason: `CASE WHEN a = 'x' THEN 1 ELSE 2 END` has its
   *   only `Var` inside a `WHEN` condition, never in [resultExpressions] or [defaultResult].
   */
  data class CaseExpr(
    val resultExpressions: List<PgNodeExpression>,
    val defaultResult: PgNodeExpression?,
    val testExpression: PgNodeExpression? = null,
    val whenConditions: List<PgNodeExpression> = emptyList(),
  ) : PgNodeExpression

  /**
   * @property boolOperator One of [BOOL_OPERATOR_AND], [BOOL_OPERATOR_OR], or [BOOL_OPERATOR_NOT].
   */
  data class BoolExpr(val arguments: List<PgNodeExpression>, val boolOperator: String) : PgNodeExpression

  data class RelabelType(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceViaIo(val argument: PgNodeExpression) : PgNodeExpression
  data class ArrayCoerceExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CollateExpr(val argument: PgNodeExpression) : PgNodeExpression
  data class CoerceToDomain(val argument: PgNodeExpression) : PgNodeExpression

  data class SqlValueFunction(val operation: Int) : PgNodeExpression

  /**
   * @property nullTestType [NULL_TEST_IS_NULL] for `IS NULL`, [NULL_TEST_IS_NOT_NULL] for `IS NOT NULL`.
   */
  data class NullTest(val argument: PgNodeExpression, val nullTestType: Int) : PgNodeExpression
  data class BooleanTest(val argument: PgNodeExpression) : PgNodeExpression
  data class DistinctExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression
  data class ArrayExpr(val elements: List<PgNodeExpression>) : PgNodeExpression
  data class RowExpr(val arguments: List<PgNodeExpression>) : PgNodeExpression
  data class NextValExpr(val sequenceOid: Int) : PgNodeExpression
  data class GroupingFunc(val arguments: List<PgNodeExpression>) : PgNodeExpression

  data class FieldSelect(val argument: PgNodeExpression, val fieldNumber: Int) : PgNodeExpression

  data class JsonIsPredicate(val argument: PgNodeExpression) : PgNodeExpression

  /**
   * A `JSON_OBJECT`, `JSON_ARRAY`, `JSON_OBJECTAGG`, `JSON_ARRAYAGG`, `JSON()`, `JSON_SCALAR`, or
   * `JSON_SERIALIZE` constructor call (`JSONCONSTRUCTOREXPR` in `primnodes.h`).
   *
   * @property type The `JsonConstructorType` code — see the `JSON_CONSTRUCTOR_TYPE_*` constants below.
   * @property arguments The parsed `:args` list: each flattened key/value pair for `OBJECT`, each
   *   element for `ARRAY`, the single value argument for `PARSE`/`SCALAR`/`SERIALIZE`. Always empty
   *   for `OBJECTAGG`/`ARRAYAGG`, which put the underlying aggregate in [function] instead.
   * @property function The parsed `:func` node — `null` (from `:func <>`) for every type except
   *   `OBJECTAGG`/`ARRAYAGG`, where it holds the [Aggref] (or [WindowFunc], when `OVER` is used) that
   *   computes the aggregated JSON value. [NodeTreeNullabilityAnalyzer.isNonNull] recurses into this
   *   for those two types rather than [arguments], whose emptiness would make an
   *   `arguments.all { ... }` rule vacuously `true`.
   */
  data class JsonConstructorExpr(
    val type: Int,
    val arguments: List<PgNodeExpression>,
    val function: PgNodeExpression? = null,
  ) : PgNodeExpression
  data class JsonExpr(
    val op: Int,
    val argument: PgNodeExpression,
    val onEmpty: Int,
    val onEmptyDefault: PgNodeExpression?,
    val onError: Int,
    val onErrorDefault: PgNodeExpression?,
  ) : PgNodeExpression

  data class XmlExpr(val op: Int, val arguments: List<PgNodeExpression>) : PgNodeExpression

  /** Fallback for unrecognized node types. Safe default: nullable. */
  data class Unknown(val nodeType: String) : PgNodeExpression

  companion object {
    // SubLinkType (primnodes.h): EXISTS_SUBLINK=0, ALL_SUBLINK=1, ANY_SUBLINK=2,
    // ROWCOMPARE_SUBLINK=3, EXPR_SUBLINK=4, MULTIEXPR_SUBLINK=5, ARRAY_SUBLINK=6, CTE_SUBLINK=7.
    // Only the values this analyzer distinguishes are named; EXPR/MULTIEXPR/CTE are never specially
    // handled and fall through to nullable by matching none of them. `a NOT IN (...)` is an
    // ANY_SUBLINK wrapped in a `BOOLEXPR :boolop not`, not a distinct type.
    const val SUBLINK_TYPE_EXISTS: Int = 0 // EXISTS_SUBLINK
    const val SUBLINK_TYPE_ALL: Int = 1 // ALL_SUBLINK
    const val SUBLINK_TYPE_ANY: Int = 2 // ANY_SUBLINK
    const val SUBLINK_TYPE_ROWCOMPARE: Int = 3 // ROWCOMPARE_SUBLINK
    const val SUBLINK_TYPE_ARRAY: Int = 6 // ARRAY_SUBLINK

    // JsonExprOp (primnodes.h) — introduced in PG 17. JSON_EXISTS=0, JSON_QUERY=1, JSON_VALUE=2,
    // JSON_TABLE=3. A JSON_TABLE COLUMNS clause does emit :op 3 nodes, but they live inside a range
    // table entry's :tablefunc (rtekind 4), which parseExpression never descends into — a JSON_TABLE
    // column resolves in the outer query as a plain VAR against that RTE. So JSON_TABLE_OP is named
    // for completeness and deliberately has no branch in evaluateJsonExpr. There is no
    // JSON_SERIALIZE_OP: `JSON_SERIALIZE(...)` emits a JSONCONSTRUCTOREXPR (`:type 7`) instead.
    const val JSON_EXISTS_OP: Int = 0
    const val JSON_QUERY_OP: Int = 1
    const val JSON_VALUE_OP: Int = 2
    const val JSON_TABLE_OP: Int = 3

    // JsonBehaviorType (primnodes.h). Each code named here is one NodeTreeNullabilityAnalyzer's
    // evaluateJsonExpr recognizes as producing a definite outcome for JSON_QUERY's ON EMPTY/ON
    // ERROR clause or JSON_EXISTS's ON ERROR clause; an unrecognized or unnamed code is treated as
    // nullable, the safe default.
    const val JSON_BEHAVIOR_NULL: Int = 0
    const val JSON_BEHAVIOR_ERROR: Int = 1
    const val JSON_BEHAVIOR_TRUE: Int = 3
    const val JSON_BEHAVIOR_FALSE: Int = 4
    const val JSON_BEHAVIOR_UNKNOWN: Int = 5
    const val JSON_BEHAVIOR_EMPTY_ARRAY: Int = 6
    const val JSON_BEHAVIOR_EMPTY_OBJECT: Int = 7
    const val JSON_BEHAVIOR_DEFAULT: Int = 8

    // BoolExprType (primnodes.h)
    const val BOOL_OPERATOR_AND: String = "and"
    const val BOOL_OPERATOR_OR: String = "or"
    const val BOOL_OPERATOR_NOT: String = "not"

    // NullTestType (primnodes.h)
    const val NULL_TEST_IS_NULL: Int = 0
    const val NULL_TEST_IS_NOT_NULL: Int = 1

    // VarReturningType (primnodes.h) — PostgreSQL 18+ only; see [Var.returningType]'s KDoc
    const val VAR_RETURNING_TYPE_NORMAL: Int = 0
    const val VAR_RETURNING_TYPE_OLD: Int = 1
    const val VAR_RETURNING_TYPE_NEW: Int = 2

    // JsonConstructorType (primnodes.h): JSON_OBJECT=1, JSON_ARRAY=2, JSON_OBJECTAGG=3,
    // JSON_ARRAYAGG=4, JSON_PARSE (`JSON()`)=5, JSON_SCALAR=6, JSON_SERIALIZE=7. Types 1-4 exist back
    // to PostgreSQL 16; types 5-7 need 17, where on 16 `json(a_column)` is the pre-standard I/O cast
    // (an ordinary COERCEVIAIO node this parser already handles) and json_scalar/json_serialize do not
    // exist at all, so no version-conditional logic is needed.
    const val JSON_CONSTRUCTOR_TYPE_OBJECT: Int = 1
    const val JSON_CONSTRUCTOR_TYPE_ARRAY: Int = 2
    const val JSON_CONSTRUCTOR_TYPE_OBJECTAGG: Int = 3
    const val JSON_CONSTRUCTOR_TYPE_ARRAYAGG: Int = 4
    const val JSON_CONSTRUCTOR_TYPE_PARSE: Int = 5
    const val JSON_CONSTRUCTOR_TYPE_SCALAR: Int = 6
    const val JSON_CONSTRUCTOR_TYPE_SERIALIZE: Int = 7

    // XmlExprOp (primnodes.h)
    const val XML_IS_XMLCONCAT: Int = 0
    const val XML_IS_XMLELEMENT: Int = 1
    const val XML_IS_XMLFOREST: Int = 2
    const val XML_IS_XMLPARSE: Int = 3
    const val XML_IS_XMLPI: Int = 4
    const val XML_IS_XMLROOT: Int = 5
    const val XML_IS_XMLSERIALIZE: Int = 6
  }
}

/**
 * A CTE (Common Table Expression) definition parsed from a `{COMMONTABLEEXPR ...}` block
 * in the `:cteList` of a query's node tree.
 *
 * Named with a `NodeTree` prefix to distinguish from [CteDefinition] in `SqlUtils.kt`, which
 * represents SQL-text-level CTE positions for DML transformation.
 *
 * @property name The CTE name (from `:ctename`).
 * @property queryBlock The full `{QUERY ...}` block text of the CTE's body (from `:ctequery`).
 * @property recursive Whether this CTE is one term of a `WITH RECURSIVE` (from `:cterecursive`).
 *   [NodeTreeProvenanceResolver] refuses to attribute a result column to a recursive CTE's body —
 *   the "same" `resno` position can be fed by a different branch on every recursive iteration, so
 *   no single body position honestly describes the whole CTE's output.
 */
internal data class NodeTreeCteDefinition(val name: String, val queryBlock: String, val recursive: Boolean = false)

/**
 * A CTE range-table-entry reference (`rtekind 6`) parsed from a query block's own `:rtable`.
 *
 * @property name The referenced CTE's name (from `:ctename`).
 * @property ctelevelsup `0` when the CTE is declared in the same query block's own `:cteList`; a
 *   value greater than `0` means the declaration is that many query levels further up, in an
 *   enclosing scope. The distinction is required when a block declares its own `WITH c` that shadows
 *   an enclosing `WITH c` of the same name with a different body — see
 *   [ColumnNullabilityAnalyzer.analyzeQueryBlockNullability].
 * @property selfReference Whether this specific reference is a recursive CTE's own recursive term
 *   referring back to itself (from `:self_reference`). `false` for every ordinary CTE reference —
 *   this is only ever `true` inside a `WITH RECURSIVE` CTE's own recursive query term. Defaults to
 *   `false` since only [RangeTableEntry.Cte] (built by [PgNodeTreeParser.parseRangeTableEntries])
 *   currently reads it; [PgNodeTreeParser.parseCteRangeTableEntries]'s existing callers never did.
 */
internal data class NodeTreeCteReference(val name: String, val ctelevelsup: Int, val selfReference: Boolean = false)

/**
 * A single range-table entry, keyed by 1-based `varno`, covering every `rtekind` — unlike
 * [PgNodeTreeParser.parseRangeTable] ([Relation] only), [PgNodeTreeParser.parseSubqueryRangeTable]
 * ([Subquery] only), and [PgNodeTreeParser.parseCteRangeTableEntries] ([Cte] only), which each
 * recognize exactly one kind and silently skip every entry of any other kind.
 * [NodeTreeProvenanceResolver] walks an arbitrary `Var`'s `varno` and must be able to see an
 * unrecognized or not-yet-modeled kind ([Other]) so it can bail rather than misinterpret that varno
 * as one of the recognized kinds.
 *
 * Built by [PgNodeTreeParser.parseRangeTableEntries].
 */
internal sealed interface RangeTableEntry {

  /** `rtekind 0`: an ordinary base table or view. */
  data class Relation(val relid: Int) : RangeTableEntry

  /** `rtekind 1`: a derived table (a subquery in `FROM`), NOT a CTE. */
  data class Subquery(val queryBlock: String) : RangeTableEntry

  /** `rtekind 6`: a reference to a CTE declared by a `WITH` clause. */
  data class Cte(val reference: NodeTreeCteReference) : RangeTableEntry

  /**
   * `rtekind 2`: a `JOIN` (including its `USING`/`NATURAL`-merged output columns).
   *
   * @property joinAliasVars One parsed expression per join output column, in order — 1-based
   *   `varattno - 1` indexes into this list. An ordinary (non-merged) column's entry is a bare
   *   [PgNodeExpression.Var] pointing at whichever side produced it; a `USING`/`NATURAL`-merged
   *   column's entry is a [PgNodeExpression.CoalesceExpr] of the two sides' Vars. That distinction
   *   is exactly what [NodeTreeProvenanceResolver.resolveVar] relies on: it casts an entry `as?
   *   Var` and bails (`return null`) when it is anything else — a `CoalesceExpr` under an outer
   *   join, whose honest provenance is not one side's expression alone — so the type-specific
   *   `jointype`/`joinmergedcols`/`joinleftcols`/`joinrightcols` fields PostgreSQL also serializes
   *   here are never needed to make that call and are not parsed into this class at all.
   */
  data class Join(val joinAliasVars: List<PgNodeExpression>) : RangeTableEntry

  /**
   * Any `rtekind` this parser does not model individually: `3` (function), `4` (tablefunc, e.g.
   * `JSON_TABLE`), `5` (`VALUES`), `7` (named tuplestore), `8` (result, a FROM-less `SELECT`), or
   * `9` (a PostgreSQL 18+ `*GROUP*` RTE — see [PgNodeTreeParser.parseGroupRteMap]). Named
   * `rtekind`, not e.g. `kind`, to match the field name so a reader cross-referencing raw
   * `pg_node_tree` text does not need to translate.
   */
  data class Other(val rtekind: Int) : RangeTableEntry
}

/**
 * A single result column from a query's `targetList`.
 *
 * @property expression The parsed expression node for this column.
 * @property resultName The column alias or name (from `:resname`), with `pg_node_tree`
 *   backslash-escaping already removed (an alias `k}x` round-trips as exactly `k}x`, never the raw
 *   `k\}x` — see [PgNodeTreeParser]'s class-level note on escaping). `null` if absent.
 * @property resultNumber The 1-based column position (from `:resno`).
 * @property isJunk Whether this entry is a junk column (`:resjunk true`), which should be excluded from results.
 * @property sortGroupRef The entry's `:ressortgroupref` value, `0` when the entry is not a `GROUP BY`/`ORDER
 *   BY`/`DISTINCT` key. When non-zero and this value appears among the `:tleSortGroupRef`s referenced by
 *   `:groupClause`/`:groupingSets`, this entry IS a `GROUP BY` grouping key — see
 *   [NodeTreeNullabilityAnalyzer]'s GROUPING SETS/CUBE/ROLLUP handling.
 * @property originalTableOid The entry's `:resorigtbl` value — the OID of the real relation this
 *   column ultimately traces back to (PostgreSQL's own `markTargetListOrigins` walks through a CTE
 *   or subquery reference to find it, not merely the immediate FROM item), or `0` when there is no
 *   single source column (a computed expression, an aggregate, a set-operation branch, or a
 *   `USING`/`NATURAL`-merged join column).
 * @property originalColumnNumber The entry's `:resorigcol` value — the source relation's 1-based
 *   attribute number — or `0` under the same conditions as [originalTableOid]. The two fields are
 *   set together: one is `0` if and only if the other is.
 */
internal data class TargetEntry(
  val expression: PgNodeExpression,
  val resultName: String?,
  val resultNumber: Int,
  val isJunk: Boolean,
  val sortGroupRef: Int = 0,
  val originalTableOid: Int = 0,
  val originalColumnNumber: Int = 0,
)
