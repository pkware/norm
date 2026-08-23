package norm.generator

/**
 * The `pg_catalog` function, cast, and operator signatures independently confirmed, against a
 * live PostgreSQL 18 instance, to be TOTAL on non-null input -- proven for every combination of
 * non-null arguments, not merely "typical" ones, including infinite, empty, or unbounded edge
 * values. Backs [PgCatalogLoader.neverNullForNonNullInputOids] via
 * [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES], [PgCatalogLoader.NEVER_NULL_CAST_SIGNATURES],
 * and [PgCatalogLoader.NEVER_NULL_OPERATOR_SIGNATURES] -- those companion-object properties
 * delegate here so this DATA can live in its own file, separate from [PgCatalogLoader]'s own job
 * of loading catalog metadata, while every existing qualified reference to
 * `PgCatalogLoader.NEVER_NULL_*` keeps resolving unchanged.
 *
 * [SafeListSweepTest] verifies this list against a live server: every listed signature actually
 * exists in `pg_catalog`, and a live sweep of representative edge-value arguments never observes a
 * `null` result for one.
 */
internal object NeverNullSafeLists {
  /**
   * Exact `(pg_proc.proname, argument type signature)` pairs safe-listed for
   * [neverNullForNonNullInputOids]. Restricted to `pronamespace = 'pg_catalog'` at query time.
   * Argument types are matched by `pg_type.typname` in declaration order — e.g. `listOf("text",
   * "text")` matches only the two-`text`-argument overload of a name, not a same-arity overload
   * over different types. A signature is listed here ONLY when it was independently confirmed,
   * against a live PostgreSQL 18 instance, to be total on every non-null, well-typed input —
   * including infinite, empty, or unbounded edge values, not merely "typical" ones. Below is
   * that audit, one entry per bullet. Every `pg_catalog` overload actually present for each name
   * is listed even where excluded, so an omission is visibly a decision rather than an oversight.
   *
   * - **upper/lower**: `pg_catalog` overloads are `(text)`, `(anyrange)`, `(anymultirange)`.
   *   `(text)` is total (kept). `(anyrange)`/`(anymultirange)` are STRICT but return `null` for a
   *   non-null, non-empty, UNBOUNDED range/multirange (`upper(int4range '[1,)')`) or an EMPTY one
   *   (`lower(int4range 'empty')`, `lower(int4multirange '{}')`) — excluded.
   * - **initcap**: only overload is `(text)` — total (kept).
   * - **length**: overloads are `(text)`, `(bytea)`, `(bytea, name)` (length in a named
   *   encoding), `(bit)`, `(bpchar)`, `(lseg)`, `(path)`, `(tsvector)`. All total on non-null
   *   input — a degenerate zero-length `lseg`/single-point `path` returns `0`, not `null`; an
   *   unrecognized encoding name errors rather than returning `null` (all kept).
   * - **char_length**: overloads are `(text)`, `(bpchar)` — both total (kept).
   * - **btrim/ltrim/rtrim**: overloads are `(text)`, `(text, text)`, `(bytea, bytea)` — all total,
   *   including on an empty trim-characters argument (kept).
   * - **replace**: only overload is `(text, text, text)` — total (kept).
   * - **split_part**: only overload is `(text, text, int4)` — total, including a field position
   *   past the actual field count (returns `''`, not `null`) and a negative position counting
   *   from the end (kept).
   * - **strpos**: only overload is `(text, text)` — total, including empty needle/haystack
   *   (returns `0`/`1`, not `null`) (kept).
   * - **md5**: overloads are `(text)`, `(bytea)` — both total, including on empty input (kept).
   * - **abs**: overloads are `(int2)`, `(int4)`, `(int8)`, `(numeric)`, `(float4)`, `(float8)` —
   *   no `(interval)` overload exists in `pg_catalog` (interval's absolute value is the `@`
   *   OPERATOR, a distinct catalog entry, not a same-named function). All six are total,
   *   including on `NaN`/`Infinity` (kept).
   * - **round**: overloads are `(float8)`, `(numeric)`, `(numeric, int4)` — all total, including
   *   on `NaN`/`Infinity` (kept).
   * - **floor/ceil/ceiling**: overloads are `(float8)`, `(numeric)` — all total, including on
   *   `NaN`/`Infinity` (kept).
   * - **now**: only overload is `()` — total (kept).
   * - **date_trunc**: overloads are `(text, interval)`, `(text, timestamp)`, `(text,
   *   timestamptz)`, `(text, timestamptz, text)`. Unlike `extract`/`date_part` below,
   *   `date_trunc` special-cases infinite input for EVERY truncation field, verified across
   *   `hour`, `microseconds`, `week`, `quarter`, `day` on `'infinity'`/`'-infinity'` values of
   *   all three temporal types, plus the 3-argument timezone-name form — all preserve infinity
   *   rather than returning `null`, and an unrecognized field name or timezone name errors rather
   *   than returning `null` (all kept).
   * - **date_part/extract**: overloads are `(text, date)`, `(text, interval)`, `(text, time)`,
   *   `(text, timestamp)`, `(text, timestamptz)`, `(text, timetz)`. UNLIKE `date_trunc`, these
   *   only special-case a FEW fields (`epoch`, `year`, `century`, ...) for infinite input — most
   *   other fields silently return `null` for a non-null, well-typed infinite value:
   *   `extract(hour FROM 'infinity'::timestamp)`, `extract(month FROM 'infinity'::interval)`, and
   *   `extract(day FROM 'infinity'::date)` (`date` supports `'infinity'` too) all return `null`
   *   with NO error. `(text, date)`, `(text, interval)`, `(text, timestamp)`, `(text,
   *   timestamptz)` are therefore EXCLUDED. `(text, time)` and `(text, timetz)` have no infinite
   *   representation for their base type and were verified total across every valid field
   *   (`hour`, `minute`, `second`, `microseconds`, `milliseconds`, `epoch`,
   *   `timezone`/`timezone_hour`/`timezone_minute` for `timetz`) plus an unrecognized field name
   *   (errors, does not return `null`) — kept.
   * - **to_char**: DROPPED ENTIRELY. `to_char(timestamp, '')` (an empty, non-null, well-typed
   *   format string) returns `null` with no error — confirmed on `pg_catalog`'s
   *   `(timestamp, text)` overload; the same risk was not individually re-verified for every
   *   other `to_char` overload (`bigint`, `float8`, `int4`, `interval`, `numeric`, `float4`,
   *   `timestamptz`, each paired with `text`) but is assumed present, since the empty-format
   *   behavior is a property of `to_char`'s shared formatting engine, not of the first
   *   argument's type.
   * - **ntile**: only window-function overload is `(int4)` — errors (not `null`) for a
   *   non-positive bucket count, total otherwise (kept).
   * - **encode/decode**: overloads are `(bytea, text)` and `(text, text)` respectively — both
   *   total on empty input; an unrecognized format name errors rather than returning `null`
   *   (kept).
   */
  internal val NEVER_NULL_FUNCTION_SIGNATURES: List<SafeFunctionSignature> = listOf(
    SafeFunctionSignature("upper", listOf("text")),
    SafeFunctionSignature("lower", listOf("text")),
    SafeFunctionSignature("initcap", listOf("text")),
    SafeFunctionSignature("length", listOf("text")),
    SafeFunctionSignature("length", listOf("bytea")),
    SafeFunctionSignature("length", listOf("bytea", "name")),
    SafeFunctionSignature("length", listOf("bit")),
    SafeFunctionSignature("length", listOf("bpchar")),
    SafeFunctionSignature("length", listOf("lseg")),
    SafeFunctionSignature("length", listOf("path")),
    SafeFunctionSignature("length", listOf("tsvector")),
    SafeFunctionSignature("char_length", listOf("text")),
    SafeFunctionSignature("char_length", listOf("bpchar")),
    SafeFunctionSignature("btrim", listOf("text")),
    SafeFunctionSignature("btrim", listOf("text", "text")),
    SafeFunctionSignature("btrim", listOf("bytea", "bytea")),
    SafeFunctionSignature("ltrim", listOf("text")),
    SafeFunctionSignature("ltrim", listOf("text", "text")),
    SafeFunctionSignature("ltrim", listOf("bytea", "bytea")),
    SafeFunctionSignature("rtrim", listOf("text")),
    SafeFunctionSignature("rtrim", listOf("text", "text")),
    SafeFunctionSignature("rtrim", listOf("bytea", "bytea")),
    SafeFunctionSignature("replace", listOf("text", "text", "text")),
    SafeFunctionSignature("split_part", listOf("text", "text", "int4")),
    SafeFunctionSignature("strpos", listOf("text", "text")),
    SafeFunctionSignature("md5", listOf("text")),
    SafeFunctionSignature("md5", listOf("bytea")),
    SafeFunctionSignature("abs", listOf("int2")),
    SafeFunctionSignature("abs", listOf("int4")),
    SafeFunctionSignature("abs", listOf("int8")),
    SafeFunctionSignature("abs", listOf("numeric")),
    SafeFunctionSignature("abs", listOf("float4")),
    SafeFunctionSignature("abs", listOf("float8")),
    SafeFunctionSignature("round", listOf("float8")),
    SafeFunctionSignature("round", listOf("numeric")),
    SafeFunctionSignature("round", listOf("numeric", "int4")),
    SafeFunctionSignature("floor", listOf("float8")),
    SafeFunctionSignature("floor", listOf("numeric")),
    SafeFunctionSignature("ceil", listOf("float8")),
    SafeFunctionSignature("ceil", listOf("numeric")),
    SafeFunctionSignature("ceiling", listOf("float8")),
    SafeFunctionSignature("ceiling", listOf("numeric")),
    SafeFunctionSignature("now", emptyList()),
    SafeFunctionSignature("date_trunc", listOf("text", "interval")),
    SafeFunctionSignature("date_trunc", listOf("text", "timestamp")),
    SafeFunctionSignature("date_trunc", listOf("text", "timestamptz")),
    SafeFunctionSignature("date_trunc", listOf("text", "timestamptz", "text")),
    SafeFunctionSignature("date_part", listOf("text", "time")),
    SafeFunctionSignature("date_part", listOf("text", "timetz")),
    SafeFunctionSignature("extract", listOf("text", "time")),
    SafeFunctionSignature("extract", listOf("text", "timetz")),
    SafeFunctionSignature("ntile", listOf("int4")),
    SafeFunctionSignature("encode", listOf("bytea", "text")),
    SafeFunctionSignature("decode", listOf("text", "text")),
    SafeFunctionSignature("left", listOf("text", "int4")),
    SafeFunctionSignature("right", listOf("text", "int4")),
    SafeFunctionSignature("substr", listOf("text", "int4")),
    SafeFunctionSignature("substr", listOf("text", "int4", "int4")),
    SafeFunctionSignature("substr", listOf("bytea", "int4")),
    SafeFunctionSignature("substr", listOf("bytea", "int4", "int4")),
    SafeFunctionSignature("repeat", listOf("text", "int4")),
    SafeFunctionSignature("lpad", listOf("text", "int4")),
    SafeFunctionSignature("lpad", listOf("text", "int4", "text")),
    SafeFunctionSignature("rpad", listOf("text", "int4")),
    SafeFunctionSignature("rpad", listOf("text", "int4", "text")),
    SafeFunctionSignature("reverse", listOf("text")),
    SafeFunctionSignature("reverse", listOf("bytea")),
    SafeFunctionSignature("position", listOf("text", "text")),
    SafeFunctionSignature("position", listOf("bytea", "bytea")),
    SafeFunctionSignature("position", listOf("bit", "bit")),
    SafeFunctionSignature("translate", listOf("text", "text", "text")),
    SafeFunctionSignature("octet_length", listOf("text")),
    SafeFunctionSignature("octet_length", listOf("bytea")),
    SafeFunctionSignature("octet_length", listOf("bpchar")),
    SafeFunctionSignature("octet_length", listOf("bit")),
    SafeFunctionSignature("ascii", listOf("text")),
    SafeFunctionSignature("chr", listOf("int4")),
    SafeFunctionSignature("sqrt", listOf("float8")),
    SafeFunctionSignature("sqrt", listOf("numeric")),
    SafeFunctionSignature("power", listOf("float8", "float8")),
    SafeFunctionSignature("power", listOf("numeric", "numeric")),
    SafeFunctionSignature("mod", listOf("int2", "int2")),
    SafeFunctionSignature("mod", listOf("int4", "int4")),
    SafeFunctionSignature("mod", listOf("int8", "int8")),
    SafeFunctionSignature("mod", listOf("numeric", "numeric")),
    SafeFunctionSignature("div", listOf("numeric", "numeric")),
    SafeFunctionSignature("trunc", listOf("float8")),
    SafeFunctionSignature("trunc", listOf("numeric")),
    SafeFunctionSignature("trunc", listOf("numeric", "int4")),
    SafeFunctionSignature("sign", listOf("float8")),
    SafeFunctionSignature("sign", listOf("numeric")),
    SafeFunctionSignature("cardinality", listOf("anyarray")),
    SafeFunctionSignature("array_to_string", listOf("anyarray", "text")),
    SafeFunctionSignature("array_to_string", listOf("anyarray", "text", "text")),
  )

  /**
   * (Source type, target type) pairs safe-listed for [neverNullForNonNullInputOids], keyed by
   * `pg_type.typname` on both sides and restricted to `pg_cast.castfunc`-backed casts (`pg_cast`
   * rows implemented by an I/O-conversion (`castmethod = 'i'`) rather than a `castfunc` — e.g.
   * `text` → `integer`, `text` → `date`, `text` → `boolean`, any `enum` ↔ `text` — need no entry
   * here at all: those surface in the node tree as [PgNodeExpression.CoerceViaIo], which
   * [NodeTreeNullabilityAnalyzer.isNonNull] already treats as safe by simply recursing into the
   * argument, since a type's own input function errors on unparseable text rather than returning
   * `null`). Array-to-array casts (e.g. `text[]` → `int4[]`) likewise need no entry: they surface
   * as [PgNodeExpression.ArrayCoerceExpr], which recurses the same way.
   *
   * Several entries are SELF-casts (`numeric` → `numeric`, `bpchar` → `bpchar`, `varchar` →
   * `varchar`, `timestamp` → `timestamp`, `timestamptz` → `timestamptz`, `time` → `time`,
   * `timetz` → `timetz`, `interval` → `interval`, `bit` → `bit`). These are not no-ops: they are
   * `castfunc`-backed typmod-ENFORCEMENT casts — e.g. applying a declared length limit
   * (`VARCHAR(5)`) or precision (`NUMERIC(10,2)`, `TIMESTAMP(3)`) to an already-typed value.
   * PostgreSQL represents "assign this literal to a length/precision-constrained column" as a
   * same-type cast through this function, not as a no-op RelabelType, which is why
   * `varchar(5)`-typed columns need `varchar` → `varchar` listed explicitly rather than falling
   * out of some other rule. Every one of these was verified to either preserve the value or
   * silently truncate/round it (`'abcdef'::varchar(3)` truncates to `'abc'`, never `null`) by the
   * same sweep as every other entry here.
   *
   * NOT keyed by source type alone: `jsonb` → `integer`/`numeric`/`boolean`/etc. and
   * `timestamp`/`timestamptz` → `time`/`timetz` are real `castfunc`-backed `pg_catalog` casts
   * that are NOT total (see [neverNullForNonNullInputOids]'s KDoc for the counterexamples), so a
   * blanket "every cast function is safe" rule — which is what this list replaced — silently
   * shipped both. Every pair actually listed here was verified total by
   * [SafeListSweepTest] against a live PostgreSQL instance using an edge-value corpus (`NaN`,
   * `Infinity`/`-Infinity`, `infinity`/`-infinity`, min/max integers, empty strings) — that sweep,
   * not hand-reasoning about any individual pair, is what licenses an entry here. When in doubt
   * whether a pair is total, the correct default is to leave it off; omission only widens a
   * result to nullable, it never narrows a truly nullable expression to non-null.
   */
  internal val NEVER_NULL_CAST_SIGNATURES: List<SafeCastSignature> = listOf(
    SafeCastSignature("int2", "int4"),
    SafeCastSignature("int2", "int8"),
    SafeCastSignature("int2", "numeric"),
    SafeCastSignature("int2", "float4"),
    SafeCastSignature("int2", "float8"),
    SafeCastSignature("int4", "int2"),
    SafeCastSignature("int4", "int8"),
    SafeCastSignature("int4", "numeric"),
    SafeCastSignature("int4", "float4"),
    SafeCastSignature("int4", "float8"),
    SafeCastSignature("int8", "int2"),
    SafeCastSignature("int8", "int4"),
    SafeCastSignature("int8", "numeric"),
    SafeCastSignature("int8", "float4"),
    SafeCastSignature("int8", "float8"),
    SafeCastSignature("numeric", "int2"),
    SafeCastSignature("numeric", "int4"),
    SafeCastSignature("numeric", "int8"),
    SafeCastSignature("numeric", "float4"),
    SafeCastSignature("numeric", "float8"),
    SafeCastSignature("numeric", "numeric"),
    SafeCastSignature("float4", "int2"),
    SafeCastSignature("float4", "int4"),
    SafeCastSignature("float4", "int8"),
    SafeCastSignature("float4", "numeric"),
    SafeCastSignature("float4", "float8"),
    SafeCastSignature("float8", "int2"),
    SafeCastSignature("float8", "int4"),
    SafeCastSignature("float8", "int8"),
    SafeCastSignature("float8", "numeric"),
    SafeCastSignature("float8", "float4"),
    SafeCastSignature("int4", "bool"),
    SafeCastSignature("bool", "int4"),
    SafeCastSignature("bool", "text"),
    SafeCastSignature("bool", "bpchar"),
    SafeCastSignature("bool", "varchar"),
    SafeCastSignature("bpchar", "bpchar"),
    SafeCastSignature("varchar", "varchar"),
    SafeCastSignature("int2", "bytea"),
    SafeCastSignature("int4", "bytea"),
    SafeCastSignature("int8", "bytea"),
    SafeCastSignature("bytea", "int2"),
    SafeCastSignature("bytea", "int4"),
    SafeCastSignature("bytea", "int8"),
    SafeCastSignature("int4", "money"),
    SafeCastSignature("int8", "money"),
    SafeCastSignature("numeric", "money"),
    SafeCastSignature("money", "numeric"),
    SafeCastSignature("int4", "bit"),
    SafeCastSignature("int8", "bit"),
    SafeCastSignature("date", "timestamptz"),
    SafeCastSignature("date", "timestamp"),
    SafeCastSignature("timestamp", "date"),
    SafeCastSignature("timestamp", "timestamptz"),
    SafeCastSignature("timestamp", "timestamp"),
    SafeCastSignature("timestamptz", "date"),
    SafeCastSignature("timestamptz", "timestamp"),
    SafeCastSignature("timestamptz", "timestamptz"),
    SafeCastSignature("time", "timetz"),
    SafeCastSignature("time", "interval"),
    SafeCastSignature("time", "time"),
    SafeCastSignature("timetz", "time"),
    SafeCastSignature("timetz", "timetz"),
    SafeCastSignature("interval", "time"),
    SafeCastSignature("interval", "interval"),
    SafeCastSignature("bit", "bit"),
  )

  /**
   * (Symbol, left operand type, right operand type) triples safe-listed for
   * [neverNullForNonNullInputOids], keyed by `pg_type.typname` on each side and restricted to
   * `oprnamespace = 'pg_catalog'` at query time. Deliberately excludes `-> ->> #> #>>` (JSON path
   * extraction — `null` on a missing key/path, even though `pg_proc.proisstrict` is `true` for
   * them) and `@@` (jsonpath match — `null` on a non-boolean result) by never listing them, and
   * excludes every `path`-typed overload of `+`/`-`/`*` (`path_add` etc. return `null`, not an
   * error, when either operand is a CLOSED path — see [neverNullForNonNullInputOids]'s KDoc).
   * There is no `jsonb`-typed entry on this list at all — [neverNullForNonNullInputOids]'s KDoc
   * covers the `jsonb` `'null'` literal counterexample in the context of CASTS, not operators.
   *
   * NOT keyed by symbol alone: a blanket "every `pg_catalog` overload of this symbol is safe"
   * rule — which is what this list replaced — is what let `path + path` through, since `+` is
   * also the totally-safe `int4 + int4`. Every triple actually listed here was verified total by
   * [SafeListSweepTest] against a live PostgreSQL instance using an edge-value corpus (`NaN`,
   * `Infinity`/`-Infinity`, `infinity`/`-infinity`, min/max integers, empty string, empty
   * array/range/multirange, unbounded range), including the containment
   * operators (`@>`, `<@`, `&&`) against concrete `integer[]`/`text[]`/`int4range`/`numrange`/
   * `tsrange`/`daterange`/`int4multirange` instantiations of their generic `anyarray`/`anyrange`/
   * `anymultirange`/`anyelement` `pg_operator` rows — a single generic row backs every concrete
   * instantiation, so one safe-listed triple per generic row covers all of them.
   */
  internal val NEVER_NULL_OPERATOR_SIGNATURES: List<SafeOperatorSignature> = listOf(
    SafeOperatorSignature("!~", "bpchar", "text"),
    SafeOperatorSignature("!~", "text", "text"),
    SafeOperatorSignature("!~*", "bpchar", "text"),
    SafeOperatorSignature("!~*", "text", "text"),
    SafeOperatorSignature("!~~", "bytea", "bytea"),
    SafeOperatorSignature("!~~", "bpchar", "text"),
    SafeOperatorSignature("!~~", "text", "text"),
    SafeOperatorSignature("!~~*", "bpchar", "text"),
    SafeOperatorSignature("!~~*", "text", "text"),
    SafeOperatorSignature("%", "int8", "int8"),
    SafeOperatorSignature("%", "int4", "int4"),
    SafeOperatorSignature("%", "numeric", "numeric"),
    SafeOperatorSignature("%", "int2", "int2"),
    SafeOperatorSignature("*", "int8", "int8"),
    SafeOperatorSignature("*", "int8", "int4"),
    SafeOperatorSignature("*", "int8", "int2"),
    SafeOperatorSignature("*", "float8", "float8"),
    SafeOperatorSignature("*", "float8", "float4"),
    SafeOperatorSignature("*", "int4", "int8"),
    SafeOperatorSignature("*", "int4", "int4"),
    SafeOperatorSignature("*", "int4", "int2"),
    SafeOperatorSignature("*", "numeric", "numeric"),
    SafeOperatorSignature("*", "float4", "float8"),
    SafeOperatorSignature("*", "float4", "float4"),
    SafeOperatorSignature("*", "int2", "int8"),
    SafeOperatorSignature("*", "int2", "int4"),
    SafeOperatorSignature("*", "int2", "int2"),
    SafeOperatorSignature("+", "int8", "int8"),
    SafeOperatorSignature("+", "int8", "int4"),
    SafeOperatorSignature("+", "int8", "int2"),
    SafeOperatorSignature("+", "float8", "float8"),
    SafeOperatorSignature("+", "float8", "float4"),
    SafeOperatorSignature("+", "int4", "int8"),
    SafeOperatorSignature("+", "int4", "int4"),
    SafeOperatorSignature("+", "int4", "int2"),
    SafeOperatorSignature("+", "numeric", "numeric"),
    SafeOperatorSignature("+", "float4", "float8"),
    SafeOperatorSignature("+", "float4", "float4"),
    SafeOperatorSignature("+", "int2", "int8"),
    SafeOperatorSignature("+", "int2", "int4"),
    SafeOperatorSignature("+", "int2", "int2"),
    SafeOperatorSignature("-", "int8", "int8"),
    SafeOperatorSignature("-", "int8", "int4"),
    SafeOperatorSignature("-", "int8", "int2"),
    SafeOperatorSignature("-", "float8", "float8"),
    SafeOperatorSignature("-", "float8", "float4"),
    SafeOperatorSignature("-", "int4", "int8"),
    SafeOperatorSignature("-", "int4", "int4"),
    SafeOperatorSignature("-", "int4", "int2"),
    SafeOperatorSignature("-", "numeric", "numeric"),
    SafeOperatorSignature("-", "float4", "float8"),
    SafeOperatorSignature("-", "float4", "float4"),
    SafeOperatorSignature("-", "int2", "int8"),
    SafeOperatorSignature("-", "int2", "int4"),
    SafeOperatorSignature("-", "int2", "int2"),
    // Unary (prefix) overloads — no left operand (pg_operator.oprleft = 0). Negation errors on
    // overflow (negating a type's own minimum value) rather than returning null; unary plus is
    // a total no-op.
    SafeOperatorSignature("+", null, "int8"),
    SafeOperatorSignature("+", null, "int4"),
    SafeOperatorSignature("+", null, "int2"),
    SafeOperatorSignature("+", null, "numeric"),
    SafeOperatorSignature("+", null, "float4"),
    SafeOperatorSignature("+", null, "float8"),
    SafeOperatorSignature("-", null, "int8"),
    SafeOperatorSignature("-", null, "int4"),
    SafeOperatorSignature("-", null, "int2"),
    SafeOperatorSignature("-", null, "numeric"),
    SafeOperatorSignature("-", null, "float4"),
    SafeOperatorSignature("-", null, "float8"),
    SafeOperatorSignature("-", null, "interval"),
    // Date/time arithmetic. Binary `+`/`-` between a `date`/`timestamp`/`timestamptz` and an
    // `int4`/`interval` (and their commuted forms) are distinct pg_operator rows from the
    // numeric `+`/`-` overloads already listed above, each independently swept.
    SafeOperatorSignature("+", "date", "int4"),
    SafeOperatorSignature("+", "int4", "date"),
    SafeOperatorSignature("-", "date", "int4"),
    SafeOperatorSignature("-", "date", "date"),
    SafeOperatorSignature("+", "timestamp", "interval"),
    SafeOperatorSignature("+", "interval", "timestamp"),
    SafeOperatorSignature("-", "timestamp", "interval"),
    SafeOperatorSignature("-", "timestamp", "timestamp"),
    SafeOperatorSignature("+", "timestamptz", "interval"),
    SafeOperatorSignature("+", "interval", "timestamptz"),
    SafeOperatorSignature("-", "timestamptz", "interval"),
    SafeOperatorSignature("-", "timestamptz", "timestamptz"),
    SafeOperatorSignature("/", "int8", "int8"),
    SafeOperatorSignature("/", "int8", "int4"),
    SafeOperatorSignature("/", "int8", "int2"),
    SafeOperatorSignature("/", "float8", "float8"),
    SafeOperatorSignature("/", "float8", "float4"),
    SafeOperatorSignature("/", "int4", "int8"),
    SafeOperatorSignature("/", "int4", "int4"),
    SafeOperatorSignature("/", "int4", "int2"),
    SafeOperatorSignature("/", "numeric", "numeric"),
    SafeOperatorSignature("/", "float4", "float8"),
    SafeOperatorSignature("/", "float4", "float4"),
    SafeOperatorSignature("/", "int2", "int8"),
    SafeOperatorSignature("/", "int2", "int4"),
    SafeOperatorSignature("/", "int2", "int2"),
    SafeOperatorSignature("<", "int8", "int8"),
    SafeOperatorSignature("<", "int8", "int4"),
    SafeOperatorSignature("<", "int8", "int2"),
    SafeOperatorSignature("<", "bool", "bool"),
    SafeOperatorSignature("<", "bytea", "bytea"),
    SafeOperatorSignature("<", "bpchar", "bpchar"),
    SafeOperatorSignature("<", "date", "date"),
    SafeOperatorSignature("<", "date", "timestamptz"),
    SafeOperatorSignature("<", "date", "timestamp"),
    SafeOperatorSignature("<", "float8", "float8"),
    SafeOperatorSignature("<", "float8", "float4"),
    SafeOperatorSignature("<", "int4", "int8"),
    SafeOperatorSignature("<", "int4", "int4"),
    SafeOperatorSignature("<", "int4", "int2"),
    SafeOperatorSignature("<", "interval", "interval"),
    SafeOperatorSignature("<", "numeric", "numeric"),
    SafeOperatorSignature("<", "float4", "float8"),
    SafeOperatorSignature("<", "float4", "float4"),
    SafeOperatorSignature("<", "int2", "int8"),
    SafeOperatorSignature("<", "int2", "int4"),
    SafeOperatorSignature("<", "int2", "int2"),
    SafeOperatorSignature("<", "text", "text"),
    SafeOperatorSignature("<", "timetz", "timetz"),
    SafeOperatorSignature("<", "time", "time"),
    SafeOperatorSignature("<", "timestamptz", "date"),
    SafeOperatorSignature("<", "timestamptz", "timestamptz"),
    SafeOperatorSignature("<", "timestamptz", "timestamp"),
    SafeOperatorSignature("<", "timestamp", "date"),
    SafeOperatorSignature("<", "timestamp", "timestamptz"),
    SafeOperatorSignature("<", "timestamp", "timestamp"),
    SafeOperatorSignature("<", "uuid", "uuid"),
    SafeOperatorSignature("<=", "int8", "int8"),
    SafeOperatorSignature("<=", "int8", "int4"),
    SafeOperatorSignature("<=", "int8", "int2"),
    SafeOperatorSignature("<=", "bool", "bool"),
    SafeOperatorSignature("<=", "bytea", "bytea"),
    SafeOperatorSignature("<=", "bpchar", "bpchar"),
    SafeOperatorSignature("<=", "date", "date"),
    SafeOperatorSignature("<=", "date", "timestamptz"),
    SafeOperatorSignature("<=", "date", "timestamp"),
    SafeOperatorSignature("<=", "float8", "float8"),
    SafeOperatorSignature("<=", "float8", "float4"),
    SafeOperatorSignature("<=", "int4", "int8"),
    SafeOperatorSignature("<=", "int4", "int4"),
    SafeOperatorSignature("<=", "int4", "int2"),
    SafeOperatorSignature("<=", "interval", "interval"),
    SafeOperatorSignature("<=", "numeric", "numeric"),
    SafeOperatorSignature("<=", "float4", "float8"),
    SafeOperatorSignature("<=", "float4", "float4"),
    SafeOperatorSignature("<=", "int2", "int8"),
    SafeOperatorSignature("<=", "int2", "int4"),
    SafeOperatorSignature("<=", "int2", "int2"),
    SafeOperatorSignature("<=", "text", "text"),
    SafeOperatorSignature("<=", "timetz", "timetz"),
    SafeOperatorSignature("<=", "time", "time"),
    SafeOperatorSignature("<=", "timestamptz", "date"),
    SafeOperatorSignature("<=", "timestamptz", "timestamptz"),
    SafeOperatorSignature("<=", "timestamptz", "timestamp"),
    SafeOperatorSignature("<=", "timestamp", "date"),
    SafeOperatorSignature("<=", "timestamp", "timestamptz"),
    SafeOperatorSignature("<=", "timestamp", "timestamp"),
    SafeOperatorSignature("<=", "uuid", "uuid"),
    SafeOperatorSignature("<>", "int8", "int8"),
    SafeOperatorSignature("<>", "int8", "int4"),
    SafeOperatorSignature("<>", "int8", "int2"),
    SafeOperatorSignature("<>", "bool", "bool"),
    SafeOperatorSignature("<>", "bytea", "bytea"),
    SafeOperatorSignature("<>", "bpchar", "bpchar"),
    SafeOperatorSignature("<>", "date", "date"),
    SafeOperatorSignature("<>", "date", "timestamptz"),
    SafeOperatorSignature("<>", "date", "timestamp"),
    SafeOperatorSignature("<>", "float8", "float8"),
    SafeOperatorSignature("<>", "float8", "float4"),
    SafeOperatorSignature("<>", "int4", "int8"),
    SafeOperatorSignature("<>", "int4", "int4"),
    SafeOperatorSignature("<>", "int4", "int2"),
    SafeOperatorSignature("<>", "interval", "interval"),
    SafeOperatorSignature("<>", "numeric", "numeric"),
    SafeOperatorSignature("<>", "float4", "float8"),
    SafeOperatorSignature("<>", "float4", "float4"),
    SafeOperatorSignature("<>", "int2", "int8"),
    SafeOperatorSignature("<>", "int2", "int4"),
    SafeOperatorSignature("<>", "int2", "int2"),
    SafeOperatorSignature("<>", "text", "text"),
    SafeOperatorSignature("<>", "timetz", "timetz"),
    SafeOperatorSignature("<>", "time", "time"),
    SafeOperatorSignature("<>", "timestamptz", "date"),
    SafeOperatorSignature("<>", "timestamptz", "timestamptz"),
    SafeOperatorSignature("<>", "timestamptz", "timestamp"),
    SafeOperatorSignature("<>", "timestamp", "date"),
    SafeOperatorSignature("<>", "timestamp", "timestamptz"),
    SafeOperatorSignature("<>", "timestamp", "timestamp"),
    SafeOperatorSignature("<>", "uuid", "uuid"),
    SafeOperatorSignature("=", "int8", "int8"),
    SafeOperatorSignature("=", "int8", "int4"),
    SafeOperatorSignature("=", "int8", "int2"),
    SafeOperatorSignature("=", "bool", "bool"),
    SafeOperatorSignature("=", "bytea", "bytea"),
    SafeOperatorSignature("=", "bpchar", "bpchar"),
    SafeOperatorSignature("=", "date", "date"),
    SafeOperatorSignature("=", "date", "timestamptz"),
    SafeOperatorSignature("=", "date", "timestamp"),
    SafeOperatorSignature("=", "float8", "float8"),
    SafeOperatorSignature("=", "float8", "float4"),
    SafeOperatorSignature("=", "int4", "int8"),
    SafeOperatorSignature("=", "int4", "int4"),
    SafeOperatorSignature("=", "int4", "int2"),
    SafeOperatorSignature("=", "interval", "interval"),
    SafeOperatorSignature("=", "numeric", "numeric"),
    SafeOperatorSignature("=", "float4", "float8"),
    SafeOperatorSignature("=", "float4", "float4"),
    SafeOperatorSignature("=", "int2", "int8"),
    SafeOperatorSignature("=", "int2", "int4"),
    SafeOperatorSignature("=", "int2", "int2"),
    SafeOperatorSignature("=", "text", "text"),
    SafeOperatorSignature("=", "timetz", "timetz"),
    SafeOperatorSignature("=", "time", "time"),
    SafeOperatorSignature("=", "timestamptz", "date"),
    SafeOperatorSignature("=", "timestamptz", "timestamptz"),
    SafeOperatorSignature("=", "timestamptz", "timestamp"),
    SafeOperatorSignature("=", "timestamp", "date"),
    SafeOperatorSignature("=", "timestamp", "timestamptz"),
    SafeOperatorSignature("=", "timestamp", "timestamp"),
    SafeOperatorSignature("=", "uuid", "uuid"),
    SafeOperatorSignature(">", "int8", "int8"),
    SafeOperatorSignature(">", "int8", "int4"),
    SafeOperatorSignature(">", "int8", "int2"),
    SafeOperatorSignature(">", "bool", "bool"),
    SafeOperatorSignature(">", "bytea", "bytea"),
    SafeOperatorSignature(">", "bpchar", "bpchar"),
    SafeOperatorSignature(">", "date", "date"),
    SafeOperatorSignature(">", "date", "timestamptz"),
    SafeOperatorSignature(">", "date", "timestamp"),
    SafeOperatorSignature(">", "float8", "float8"),
    SafeOperatorSignature(">", "float8", "float4"),
    SafeOperatorSignature(">", "int4", "int8"),
    SafeOperatorSignature(">", "int4", "int4"),
    SafeOperatorSignature(">", "int4", "int2"),
    SafeOperatorSignature(">", "interval", "interval"),
    SafeOperatorSignature(">", "numeric", "numeric"),
    SafeOperatorSignature(">", "float4", "float8"),
    SafeOperatorSignature(">", "float4", "float4"),
    SafeOperatorSignature(">", "int2", "int8"),
    SafeOperatorSignature(">", "int2", "int4"),
    SafeOperatorSignature(">", "int2", "int2"),
    SafeOperatorSignature(">", "text", "text"),
    SafeOperatorSignature(">", "timetz", "timetz"),
    SafeOperatorSignature(">", "time", "time"),
    SafeOperatorSignature(">", "timestamptz", "date"),
    SafeOperatorSignature(">", "timestamptz", "timestamptz"),
    SafeOperatorSignature(">", "timestamptz", "timestamp"),
    SafeOperatorSignature(">", "timestamp", "date"),
    SafeOperatorSignature(">", "timestamp", "timestamptz"),
    SafeOperatorSignature(">", "timestamp", "timestamp"),
    SafeOperatorSignature(">", "uuid", "uuid"),
    SafeOperatorSignature(">=", "int8", "int8"),
    SafeOperatorSignature(">=", "int8", "int4"),
    SafeOperatorSignature(">=", "int8", "int2"),
    SafeOperatorSignature(">=", "bool", "bool"),
    SafeOperatorSignature(">=", "bytea", "bytea"),
    SafeOperatorSignature(">=", "bpchar", "bpchar"),
    SafeOperatorSignature(">=", "date", "date"),
    SafeOperatorSignature(">=", "date", "timestamptz"),
    SafeOperatorSignature(">=", "date", "timestamp"),
    SafeOperatorSignature(">=", "float8", "float8"),
    SafeOperatorSignature(">=", "float8", "float4"),
    SafeOperatorSignature(">=", "int4", "int8"),
    SafeOperatorSignature(">=", "int4", "int4"),
    SafeOperatorSignature(">=", "int4", "int2"),
    SafeOperatorSignature(">=", "interval", "interval"),
    SafeOperatorSignature(">=", "numeric", "numeric"),
    SafeOperatorSignature(">=", "float4", "float8"),
    SafeOperatorSignature(">=", "float4", "float4"),
    SafeOperatorSignature(">=", "int2", "int8"),
    SafeOperatorSignature(">=", "int2", "int4"),
    SafeOperatorSignature(">=", "int2", "int2"),
    SafeOperatorSignature(">=", "text", "text"),
    SafeOperatorSignature(">=", "timetz", "timetz"),
    SafeOperatorSignature(">=", "time", "time"),
    SafeOperatorSignature(">=", "timestamptz", "date"),
    SafeOperatorSignature(">=", "timestamptz", "timestamptz"),
    SafeOperatorSignature(">=", "timestamptz", "timestamp"),
    SafeOperatorSignature(">=", "timestamp", "date"),
    SafeOperatorSignature(">=", "timestamp", "timestamptz"),
    SafeOperatorSignature(">=", "timestamp", "timestamp"),
    SafeOperatorSignature(">=", "uuid", "uuid"),
    // Unary (prefix) bitwise-complement overloads of "~" — a DIFFERENT operator from the
    // binary regex-match "~" immediately below; PostgreSQL overloads the symbol. Complementing
    // any bit pattern of a fixed-width representation always fits back in that same width, so
    // there is no overflow case the way there is for negation. `macaddr`/`macaddr8`/`inet`
    // overloads of unary "~" also exist in pg_catalog but are deliberately NOT listed — network
    // address types are outside the families this fix targets, so an expression using them
    // stays conservatively nullable rather than being swept and verified.
    SafeOperatorSignature("~", null, "int8"),
    SafeOperatorSignature("~", null, "int4"),
    SafeOperatorSignature("~", null, "int2"),
    SafeOperatorSignature("~", null, "bit"),
    SafeOperatorSignature("~", "bpchar", "text"),
    SafeOperatorSignature("~", "text", "text"),
    SafeOperatorSignature("~*", "bpchar", "text"),
    SafeOperatorSignature("~*", "text", "text"),
    SafeOperatorSignature("~~", "bytea", "bytea"),
    SafeOperatorSignature("~~", "bpchar", "text"),
    SafeOperatorSignature("~~", "text", "text"),
    SafeOperatorSignature("~~*", "bpchar", "text"),
    SafeOperatorSignature("~~*", "text", "text"),
    SafeOperatorSignature("||", "bytea", "bytea"),
    SafeOperatorSignature("||", "text", "text"),
    SafeOperatorSignature("&&", "anymultirange", "anymultirange"),
    SafeOperatorSignature("&&", "anymultirange", "anyrange"),
    SafeOperatorSignature("&&", "anyrange", "anymultirange"),
    SafeOperatorSignature("&&", "anyrange", "anyrange"),
    SafeOperatorSignature("<@", "anymultirange", "anymultirange"),
    SafeOperatorSignature("<@", "anymultirange", "anyrange"),
    SafeOperatorSignature("<@", "anyrange", "anymultirange"),
    SafeOperatorSignature("<@", "anyrange", "anyrange"),
    SafeOperatorSignature("@>", "anymultirange", "anyelement"),
    SafeOperatorSignature("@>", "anymultirange", "anymultirange"),
    SafeOperatorSignature("@>", "anymultirange", "anyrange"),
    SafeOperatorSignature("@>", "anyrange", "anyelement"),
    SafeOperatorSignature("@>", "anyrange", "anymultirange"),
    SafeOperatorSignature("@>", "anyrange", "anyrange"),
    SafeOperatorSignature("&&", "anyarray", "anyarray"),
    SafeOperatorSignature("<@", "anyarray", "anyarray"),
    SafeOperatorSignature("@>", "anyarray", "anyarray"),
  )
}
