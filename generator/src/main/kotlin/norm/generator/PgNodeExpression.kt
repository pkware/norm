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
   *   from `:funcvariadic`. In this form the LAST entry of [arguments] is the array expression
   *   itself, passed through as one value, not exploded into its elements. This matters for
   *   nullability: `concat(VARIADIC arr)` is `null` when `arr` itself is `null` (verified live on
   *   PostgreSQL 16, 17, and 18), which neither [PgCatalogLoader.alwaysNonNullFunctionOids] nor
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
   *   ANY (...)`/`a <> ALL (...)`), `null` when `:testexpr` is absent (`EXISTS`/`EXPR`/`MULTIEXPR`/
   *   `ARRAY`/`CTE` sublinks all emit no `:testexpr` — verified live across all eight `SubLinkType`
   *   values) or when it has no single-argument `:args` list this parser can read from — which is
   *   true for every multi-column row-comparison form: the `BOOLEXPR` shape (`(a, b) IN (...)`,
   *   `(a, b) <> ALL (...)`, `(a, b) = ALL (...)`) has no top-level `:args` of its own, and the
   *   `ROWCOMPAREEXPR` shape (`(a, b) < ALL (...)` and `<=`/`>`/`>=`) carries its operands under
   *   `:largs`/`:rargs` instead of `:args`, and is not a node type this parser's own `when` dispatch
   *   recognizes in the first place (parses to [Unknown]). Extracted unconditionally regardless of
   *   `subLinkType` (see [PgNodeTreeParser.parseSubLink]'s own KDoc) for consumers beyond
   *   [NodeTreeNullabilityAnalyzer]'s own `SubLink` nullability proof — see its own KDoc.
   * @property subselectBlock The raw `{QUERY ...}` text of the sublink's `:subselect`, verbatim,
   *   `null` when absent (malformed input, or a node-tree shape this parser does not model). Used
   *   by [NodeTreeNullabilityAnalyzer] to recursively analyze an `ANY_SUBLINK`'s (`IN`/`= ANY`) or
   *   `ALL_SUBLINK`'s (`x op ALL (...)`) subquery body for the "exactly one non-null output column"
   *   leg of its nullability rule — see that class's `SubLink` branch. Deliberately kept as raw text
   *   rather than a parsed [PgNodeExpression] (that type only models scalar expressions, never a
   *   whole query block), so analyzing it requires re-entering [PgNodeTreeParser]/
   *   `ColumnNullabilityAnalyzer`, not this class's own `when` dispatch.
   * @property testExpressionOperatorOid The `:opfuncid` of the sublink's `:testexpr` when that
   *   `:testexpr` is a single top-level `OPEXPR` (e.g. `a = ANY (...)`, `a IN (...)`, `a <> ALL
   *   (...)`), `null` otherwise — including the multi-column `(a, b) IN (...)`/`(a, b) <> ALL (...)`/
   *   `(a, b) = ALL (...)` forms, whose `:testexpr` is a `BOOLEXPR` combining one `OPEXPR` per column
   *   with no single top-level operator OID of its own, and the multi-column `(a, b) < ALL (...)`
   *   (and `<=`/`>`/`>=`) form, whose `:testexpr` is instead a `ROWCOMPAREEXPR` that this parser does
   *   not model at all (see [outerOperand]'s own KDoc). Used alongside [outerOperand] by
   *   [NodeTreeNullabilityAnalyzer] to confirm the sublink's comparison operator is both strict and
   *   total on non-null input before trusting the subquery column's own nullability — a `null` here
   *   means that proof is unavailable, and the sublink must default to nullable, which naturally
   *   covers every multi-column form without special-casing any of them: neither a `BOOLEXPR` nor a
   *   `ROWCOMPAREEXPR` testexpr ever yields a top-level `:opfuncid` to trust in the first place.
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
   * @property type The `JsonConstructorType` code — see the `JSON_CONSTRUCTOR_TYPE_*` constants
   *   below, each verified live (PostgreSQL 18.4, by dumping `pg_rewrite.ev_action` for a `CREATE
   *   VIEW` wrapping the corresponding SQL construct) rather than trusted from PostgreSQL source
   *   alone.
   * @property arguments The parsed `:args` list. Populated for [JSON_CONSTRUCTOR_TYPE_OBJECT] (each
   *   key/value pair, flattened), [JSON_CONSTRUCTOR_TYPE_ARRAY] (each element), and
   *   [JSON_CONSTRUCTOR_TYPE_PARSE]/[JSON_CONSTRUCTOR_TYPE_SCALAR]/[JSON_CONSTRUCTOR_TYPE_SERIALIZE]
   *   (their single value argument). ALWAYS EMPTY for [JSON_CONSTRUCTOR_TYPE_OBJECTAGG]/
   *   [JSON_CONSTRUCTOR_TYPE_ARRAYAGG] — verified live that PostgreSQL puts the underlying aggregate
   *   (or window function, when `OVER` is used) in [function] instead, leaving `:args <>` for those
   *   two types; see [function]'s own KDoc.
   * @property function The parsed `:func` node — `null` (from `:func <>`) for every type EXCEPT
   *   [JSON_CONSTRUCTOR_TYPE_OBJECTAGG]/[JSON_CONSTRUCTOR_TYPE_ARRAYAGG], where it holds the
   *   underlying [Aggref] (the ordinary case, verified live) or [WindowFunc] (verified live for
   *   `JSON_OBJECTAGG(...) OVER (...)`) that actually computes the aggregated JSON value —
   *   [NodeTreeNullabilityAnalyzer.isNonNull]'s `JsonConstructorExpr` branch recurses into this
   *   field for those two types rather than [arguments] (which is empty for them — see that
   *   property's own KDoc), routing through the EXISTING [Aggref]/[WindowFunc] nullability rules
   *   rather than duplicating them. A rule that instead required `arguments.all { ... }` for these
   *   two types would be vacuously `true` over an empty list — exactly the unsound shape the
   *   original `JsonConstructorExpr -> true` bug had, just narrower.
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
    // Verified live on PostgreSQL 18.4 by dumping prosqlbody node trees: `a <> ALL (SELECT v FROM
    // u)` and `a < ALL (SELECT v FROM u)` both emit `:subLinkType 1`; `(a, id) < (SELECT v, 1 FROM
    // u)` (a row comparison against a one-row subquery, not `ALL`/`ANY`) emits `:subLinkType 3`;
    // `a NOT IN (SELECT v FROM u)` emits `:subLinkType 2` wrapped in a `BOOLEXPR :boolop not`;
    // `EXISTS (...)` emits `0`; a plain scalar subquery emits `4`; `ARRAY(SELECT ...)` emits `6`;
    // `a = ANY (SELECT v FROM u)`/`a IN (SELECT v FROM u)` emit `2`. Only the constants this
    // analyzer distinguishes are named below — EXPR_SUBLINK, MULTIEXPR_SUBLINK, and CTE_SUBLINK are
    // never specially handled and fall through to nullable by matching none of these.
    const val SUBLINK_TYPE_EXISTS: Int = 0 // EXISTS_SUBLINK
    const val SUBLINK_TYPE_ALL: Int = 1 // ALL_SUBLINK
    const val SUBLINK_TYPE_ANY: Int = 2 // ANY_SUBLINK
    const val SUBLINK_TYPE_ROWCOMPARE: Int = 3 // ROWCOMPARE_SUBLINK
    const val SUBLINK_TYPE_ARRAY: Int = 6 // ARRAY_SUBLINK

    // JsonExprOp (primnodes.h) — introduced in PG 17. Verified live on PostgreSQL 18.4 by dumping
    // ev_action node trees: `JSON_EXISTS(...)` emits `:op 0`, `JSON_QUERY(...)` emits `:op 1`,
    // `JSON_VALUE(...)` emits `:op 2`, and a `JSON_TABLE(...)` COLUMNS clause emits `:op 3` for both
    // its whole-document `:docexpr` and each column's own `:colvalexprs` entry — but those live
    // inside a range-table entry's `:tablefunc` (rtekind 4, `RTE_TABLEFUNC`), never as an ordinary
    // expression this parser's `parseExpression` walks into; a `JSON_TABLE` column resolves in the
    // outer query as a plain `VAR` against that RTE instead (see
    // NodeTreeNullabilityAnalyzer.isKnownNonNullJsonBehavior's own KDoc, which already documents
    // this for the ON EMPTY/ON ERROR allow-list). JSON_TABLE_OP is therefore named here for
    // completeness and is deliberately UNREACHED by evaluateJsonExpr's `when` — no branch is written
    // for it, so it falls through that `when`'s own `else -> false`, the safe default, exactly like
    // any other JsonExpr code this parser might one day see in a position it does not expect.
    //
    // There is no JSON_SERIALIZE_OP: `JSON_SERIALIZE(...)` was previously (wrongly) assigned the
    // value 4 here, a value JsonExprOp does not define at all — verified live that
    // `JSON_SERIALIZE(...)` never produces a JsonExpr node in the first place, emitting a
    // JSONCONSTRUCTOREXPR (`:type 7`) instead. The dead `JSON_SERIALIZE_OP` branch this constant fed
    // in evaluateJsonExpr has been removed along with it.
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

    // JsonConstructorType (primnodes.h). Verified live by dumping pg_rewrite.ev_action for a CREATE
    // VIEW wrapping each construct:
    //  - `JSON_OBJECT('a' VALUE 1)` -> `:type 1`, `JSON_ARRAY(1, 2)` -> `:type 2` — both verified to
    //    exist and produce this same JSONCONSTRUCTOREXPR shape on PostgreSQL 16 too (the earliest
    //    version this project supports), the only two codes stable that far back.
    //  - `JSON_OBJECTAGG(k:v)`/`JSON_ARRAYAGG(v)` -> `:type 3`/`:type 4` — also verified present on
    //    PostgreSQL 16.
    //  - `JSON('{"a":1}')` -> `:type 5`, `JSON_SCALAR(1)` -> `:type 6`, `JSON_SERIALIZE(x)` ->
    //    `:type 7` — verified live to NOT produce a JSONCONSTRUCTOREXPR node at all on PostgreSQL 16:
    //    `json(text)` there resolves to the pre-SQL/JSON-standard `json` I/O CAST function instead —
    //    verified live over a real column reference (not a literal): `json(a_column)` emits an
    //    ordinary `{COERCEVIAIO :arg {VAR ...} ...}` node, a shape this parser already models via
    //    parseCoerceViaIo/PgNodeExpression.CoerceViaIo (never a JSONCONSTRUCTOREXPR). A literal
    //    argument, e.g. `json('{"a":1}')`, additionally constant-folds all the way to a plain
    //    `{CONST ...}` before that, since the whole expression is compile-time constant — but the
    //    non-folding, COERCEVIAIO shape is the one that matters here, since it is what a real,
    //    non-constant column reference actually produces. `json_scalar`/`json_serialize` do not exist
    //    as functions on PostgreSQL 16 at all (`function ... does not exist`). Verified present and
    //    producing `:type 5`/`6`/`7` respectively on both PostgreSQL 17 and 18. No version-conditional
    //    logic is needed in NodeTreeNullabilityAnalyzer for this either way: a `:type` this analyzer
    //    does not expect simply never occurs on a version whose grammar cannot produce it in the
    //    first place, and PostgreSQL 16's COERCEVIAIO/CONST shapes are both already handled by this
    //    parser's ordinary, non-JSON-specific rules.
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
 */
internal data class NodeTreeCteDefinition(val name: String, val queryBlock: String)

/**
 * A CTE range-table-entry reference (`rtekind 6`) parsed from a query block's own `:rtable` — the
 * CTE's name and how many query levels up its OWN declaration (`:cteList` entry) sits, relative to
 * the block this reference was found in.
 *
 * @property name The referenced CTE's name (from `:ctename`).
 * @property ctelevelsup `0` when the CTE is declared in the SAME query block's own `:cteList` (the
 *   block declares its own `WITH` clause containing this name); a value greater than `0` means the
 *   declaration is that many query levels further up, in an ENCLOSING scope. Distinguishing these is
 *   required to resolve a reference correctly when a block declares its own `WITH c` that shadows an
 *   enclosing `WITH c` of the same name with a different body — see
 *   [ColumnNullabilityAnalyzer.analyzeQueryBlockNullability]'s own KDoc for the caller that acts on
 *   this distinction.
 */
internal data class NodeTreeCteReference(val name: String, val ctelevelsup: Int)

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
 */
internal data class TargetEntry(
  val expression: PgNodeExpression,
  val resultName: String?,
  val resultNumber: Int,
  val isJunk: Boolean,
  val sortGroupRef: Int = 0,
)
