package norm.generator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration

/**
 * Brute-force verification, against a LIVE PostgreSQL instance, that every entry in
 * [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES], [PgCatalogLoader.NEVER_NULL_CAST_SIGNATURES],
 * [PgCatalogLoader.NEVER_NULL_OPERATOR_SIGNATURES], and pgcrypto's `digest`/`hmac` really is TOTAL
 * on non-null input — the property [NodeTreeNullabilityAnalyzer] relies on those lists for, in
 * addition to `pg_proc.proisstrict`/an equivalent implicit-strictness check, licensing the claim
 * that a call is non-null whenever every one of its arguments is non-null.
 *
 * This sweep — not hand-reasoning about a specific overload's documented behavior — is what
 * LICENSES an entry on any of these lists. For each signature, this test substitutes every
 * combination (the cartesian product across argument positions) of non-null EDGE-CASE values
 * from [EDGE_VALUE_CORPUS] for that signature's argument(s) and asserts the resulting call
 * `IS NOT NULL`. The corpus specifically targets the values that have historically broken a
 * "total" claim for some `pg_catalog` overload: empty strings, `NaN`/`Infinity`/`-Infinity`,
 * PostgreSQL's `infinity`/`-infinity` date/time sentinels, zero, negative and min/max integer
 * values, empty and unbounded ranges, empty arrays and multiranges, and a CLOSED geometric path
 * (the exact shape that broke `path + path` — see [PgCatalogLoader.NEVER_NULL_OPERATOR_SIGNATURES]).
 *
 * A case that raises a [SQLException] is a PASS only when the exception proves the call actually
 * EVALUATED and then genuinely errored — see [isNotEvaluatedSqlState] for the syntax-error /
 * undefined-function / cannot-coerce PostgreSQL SQLSTATE classes that mean the call never got that
 * far (a syntax error is not evidence about the function's runtime behavior). An actual `null`
 * RESULT is always a failure, named by the exact entry and the exact literal SQL expression that
 * produced it.
 *
 * A case being a PASS is not, by itself, enough to license a signature: [CoverageTracker] requires
 * every signature to additionally accumulate at least one case that actually EVALUATED and
 * returned a non-null value. A signature whose every case errored (or, worse, never got past
 * parsing) provides ZERO information about whether the signature is actually total, and is
 * reported as a failure in its own right — see `requirePositiveCoverage`.
 */
@Testcontainers
class SafeListSweepTest {

  @Test
  fun `every safe-listed function signature is total on the edge-value corpus`() {
    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val unresolvedByCatalog = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (signature in PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES) {
        val description = "function ${signature.name}(${signature.argumentTypeNames.joinToString(",")})"
        val coverage = CoverageTracker()
        // A pseudo-type (anyarray/anyrange/anymultirange/anyelement) argument — e.g.
        // cardinality(anyarray) — has no literal form of its own, so it must be instantiated with
        // one or more concrete types (the same way the operator sweep below does) before a literal
        // can be built. A concrete type (e.g. text) instantiates to itself, with its own corpus.
        val positionInstantiations = signature.argumentTypeNames.map { typeName -> concreteInstantiationsFor(typeName) }
        for (instantiation in cartesianProductOfInstantiations(positionInstantiations)) {
          val concreteArgumentTypeNames = instantiation.map { it.first }
          val argumentCorpora = instantiation.mapIndexed { index, (_, corpus) ->
            argumentCorpusOverride(signature.name, index) ?: corpus
          }
          for (rawArguments in cartesianProduct(argumentCorpora)) {
            caseCount++
            val expression = functionCallExpression(signature, rawArguments, concreteArgumentTypeNames)
            coverage.record(checkTotal(stmt, description, expression, failures))
          }
        }
        if (coverage.neverResolvedOnThisServer()) {
          skipped += description
        } else {
          coverage.requirePositiveCoverage(description, failures)
        }
        if (!functionResolvesOnThisServer(signature)) {
          unresolvedByCatalog += description
        }
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(skipped.sorted()).isEqualTo(unresolvedByCatalog.sorted())
    assertThat(failures).isEmpty()
  }

  @Test
  fun `every safe-listed cast signature is total on the edge-value corpus`() {
    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val unresolvedByCatalog = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (signature in PgCatalogLoader.NEVER_NULL_CAST_SIGNATURES) {
        val description = "cast ${signature.sourceTypeName}->${signature.targetTypeName}"
        val coverage = CoverageTracker()
        for (castExpression in castExpressionsFor(signature)) {
          caseCount++
          coverage.record(checkTotal(stmt, description, castExpression, failures))
        }
        if (coverage.neverResolvedOnThisServer()) {
          skipped += description
        } else {
          coverage.requirePositiveCoverage(description, failures)
        }
        if (!castResolvesOnThisServer(signature)) {
          unresolvedByCatalog += description
        }
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(skipped.sorted()).isEqualTo(unresolvedByCatalog.sorted())
    assertThat(failures).isEmpty()
  }

  @Test
  fun `every safe-listed operator signature is total on the edge-value corpus`() {
    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val unresolvedByCatalog = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (signature in PgCatalogLoader.NEVER_NULL_OPERATOR_SIGNATURES) {
        val description = "operator ${signature.symbol}(${signature.leftTypeName},${signature.rightTypeName})"
        val coverage = CoverageTracker()
        // A pseudo-type (anyarray/anyrange/anymultirange/anyelement) has no literal form of its
        // own — it must be instantiated with one or more concrete types before a literal can be
        // built. A concrete type (e.g. int4) instantiates to itself, with its own corpus.
        val leftInstantiations = signature.leftTypeName?.let { concreteInstantiationsFor(it) }
        val rightInstantiations = signature.rightTypeName?.let { concreteInstantiationsFor(it) }
        for ((leftTypeName, leftLiterals) in leftInstantiations ?: listOf(null to listOf<String?>(null))) {
          for ((rightTypeName, rightLiterals) in rightInstantiations ?: listOf(null to listOf<String?>(null))) {
            for (leftLiteral in leftLiterals) {
              for (rightLiteral in rightLiterals) {
                caseCount++
                val leftExpression = leftLiteral?.let { "$it::$leftTypeName" }
                val rightExpression = rightLiteral?.let { "$it::$rightTypeName" }
                val operatorExpression = when {
                  leftExpression != null && rightExpression != null ->
                    "$leftExpression ${signature.symbol} $rightExpression"

                  leftExpression != null -> "$leftExpression ${signature.symbol}"
                  rightExpression != null -> "${signature.symbol} $rightExpression"
                  else -> error("Operator signature $signature has neither a left nor a right operand")
                }
                coverage.record(checkTotal(stmt, description, operatorExpression, failures))
              }
            }
          }
        }
        if (coverage.neverResolvedOnThisServer()) {
          skipped += description
        } else {
          coverage.requirePositiveCoverage(description, failures)
        }
        if (!operatorResolvesOnThisServer(signature)) {
          unresolvedByCatalog += description
        }
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(skipped.sorted()).isEqualTo(unresolvedByCatalog.sorted())
    assertThat(failures).isEmpty()
  }

  /**
   * pgcrypto's `digest`/`hmac` are keyed through `pg_depend` rather than appearing on any of the
   * three static safe lists (see [PgCatalogLoader.loadNeverNullForNonNullInputOids]'s pgcrypto
   * carve-out), so none of the three tests above ever exercises them. This test brings all four
   * documented-total overloads (`digest(text, text)`, `digest(bytea, text)`, `hmac(text, text,
   * text)`, `hmac(bytea, bytea, text)`) into the same brute-force sweep, backing the KDoc's claim
   * that they were verified total. `postgres:$pgVersion-alpine` can `CREATE EXTENSION pgcrypto`
   * without any extra setup, so there is no need for an alternate verification path.
   */
  @Test
  fun `every pgcrypto digest and hmac signature is total on the edge-value corpus`() {
    connection.createStatement().use { stmt -> stmt.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto") }
    val failures = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (signature in PGCRYPTO_FUNCTION_SIGNATURES) {
        val description = "pgcrypto function ${signature.name}(${signature.argumentTypeNames.joinToString(",")})"
        val coverage = CoverageTracker()
        val lastArgumentIndex = signature.argumentTypeNames.lastIndex
        val argumentCorpora = signature.argumentTypeNames.mapIndexed { index, typeName ->
          if (index == lastArgumentIndex) HASH_ALGORITHM_NAME_CORPUS else edgeValueCorpusFor(typeName)
        }
        for (rawArguments in cartesianProduct(argumentCorpora)) {
          caseCount++
          coverage.record(checkTotal(stmt, description, functionCallExpression(signature, rawArguments), failures))
        }
        coverage.requirePositiveCoverage(description, failures)
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(failures).isEmpty()
  }

  /**
   * A sweep with no positive-coverage requirement, and no distinction between "evaluated and
   * errored" and "never evaluated", is a sweep that can silently rot into a no-op — it would keep
   * reporting BUILD SUCCESSFUL forever, even for a signature added later that is provably NOT
   * total, as long as every one of its cases happens to error. This test drives [checkTotal] and
   * [CoverageTracker] directly against `extract(text, timestamp)` — a signature deliberately never
   * added to [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES] because
   * `extract(hour FROM 'infinity'::timestamp)` returns `null` with no error (see that list's KDoc)
   * — and asserts the sweep machinery reports it as a failure. If this test ever passes without
   * failures being produced, the sweep's core safety property has silently stopped working.
   *
   * This test alone only proves [checkTotal] catches an actual `null` RESULT — `extract` still
   * accumulates plenty of `EvaluatedNonNull` cases from every non-infinity timestamp in the corpus,
   * so it says nothing about [CoverageTracker.requirePositiveCoverage] itself. The two tests below
   * it (`requirePositiveCoverage fails ...` / `requirePositiveCoverage passes ...`) close that gap
   * by calling `requirePositiveCoverage` directly against a synthetic zero-coverage and a synthetic
   * real-coverage [CoverageTracker], the only way to exercise the ZERO-coverage branch, since every
   * signature actually on a safe list has real coverage by construction.
   */
  @Test
  fun `checkTotal reports a failure for a known non-total signature`() {
    val mutatedSignature = SafeFunctionSignature("extract", listOf("text", "timestamp"))
    val failures = mutableListOf<String>()
    val coverage = CoverageTracker()
    connection.createStatement().use { stmt ->
      val argumentCorpora = mutatedSignature.argumentTypeNames.mapIndexed { index, typeName ->
        argumentCorpusOverride(mutatedSignature.name, index) ?: edgeValueCorpusFor(typeName)
      }
      for (rawArguments in cartesianProduct(argumentCorpora)) {
        coverage.record(
          checkTotal(
            stmt,
            "function extract(text,timestamp)",
            functionCallExpression(mutatedSignature, rawArguments),
            failures,
          ),
        )
      }
    }
    coverage.requirePositiveCoverage("function extract(text,timestamp)", failures)
    assertThat(failures).isNotEmpty()
    assertThat(failures.any { it.contains("returned NULL for") }).isTrue()
  }

  /**
   * Drives [CoverageTracker.requirePositiveCoverage] directly with a tracker that has recorded
   * only [CaseOutcome.EvaluatedError] and [CaseOutcome.NotEvaluated] outcomes — zero
   * [CaseOutcome.EvaluatedNonNull] — and asserts it reports a failure. This is the one branch
   * `checkTotal reports a failure for a known non-total signature` above cannot reach: every real
   * safe-listed signature (and even the deliberately-non-total `extract` mutant) accumulates at
   * least one `EvaluatedNonNull` case, so nothing in this file's other tests ever calls
   * `requirePositiveCoverage` against zero coverage. Fails to catch a regression if
   * `CoverageTracker`'s `minimumPositiveCases` is lowered to `0`, or if `requirePositiveCoverage`
   * is gutted into a no-op — both mutations were made and reverted by hand to confirm this test
   * actually fails under each.
   */
  @Test
  fun `requirePositiveCoverage fails for a signature with zero evaluated-non-null cases`() {
    val failures = mutableListOf<String>()
    val coverage = CoverageTracker()
    coverage.record(CaseOutcome.EvaluatedError("22003: numeric field overflow"))
    coverage.record(CaseOutcome.NotEvaluated("42883: function foo(int4) does not exist"))
    coverage.requirePositiveCoverage("function foo(int4)", failures)
    assertThat(failures).isNotEmpty()
    assertThat(failures.any { it.contains("zero cases that evaluated to a non-null value") }).isTrue()
  }

  /**
   * The positive counterpart to the test above: a [CoverageTracker] that HAS recorded a genuine
   * [CaseOutcome.EvaluatedNonNull] case reports no failure. Without this test, a
   * `requirePositiveCoverage` that always failed (rather than one that failed only on zero
   * coverage) would also make the test above pass, hiding a rule that rejects every signature.
   */
  @Test
  fun `requirePositiveCoverage passes for a signature with at least one evaluated-non-null case`() {
    val failures = mutableListOf<String>()
    val coverage = CoverageTracker()
    coverage.record(CaseOutcome.EvaluatedError("22003: numeric field overflow"))
    coverage.record(CaseOutcome.EvaluatedNonNull)
    coverage.requirePositiveCoverage("function foo(int4)", failures)
    assertThat(failures).isEmpty()
  }

  /**
   * The signature this test models: `int2->bytea`, safe-listed but only castfunc-backed starting
   * in PostgreSQL 18 — on 16/17 every corpus case dies on `42846: cannot cast type smallint to
   * bytea`, a class-42 (never-evaluated) SQLSTATE, for every single literal regardless of value.
   * [CoverageTracker.neverResolvedOnThisServer] must recognize this as a SKIP, not a failure — see
   * that method's KDoc for why "every case NotEvaluated" reliably means "does not resolve here".
   */
  @Test
  fun `neverResolvedOnThisServer is true when every case is NotEvaluated`() {
    val coverage = CoverageTracker()
    coverage.record(CaseOutcome.NotEvaluated("42846: cannot cast type smallint to bytea"))
    coverage.record(CaseOutcome.NotEvaluated("42846: cannot cast type smallint to bytea"))
    assertThat(coverage.neverResolvedOnThisServer()).isTrue()
  }

  /**
   * The counterpart to the test above: a signature that DOES resolve here but happens to error on
   * every corpus value it was given (a genuine "corpus is insufficient" bug, not a version gap)
   * must NOT be classified as a skip — that mix ([CaseOutcome.EvaluatedError] alongside
   * [CaseOutcome.NotEvaluated], or [CaseOutcome.EvaluatedError] alone) is impossible for a
   * signature that never resolves at all (see [CoverageTracker.neverResolvedOnThisServer]'s
   * KDoc), so it is left to fail normally via [CoverageTracker.requirePositiveCoverage] instead.
   */
  @Test
  fun `neverResolvedOnThisServer is false when at least one case evaluated, even only as an error`() {
    val coverage = CoverageTracker()
    coverage.record(CaseOutcome.NotEvaluated("42846: cannot cast type smallint to bytea"))
    coverage.record(CaseOutcome.EvaluatedError("22003: numeric field overflow"))
    assertThat(coverage.neverResolvedOnThisServer()).isFalse()
  }

  /** A signature with any evaluated-non-null case obviously resolved — not a skip. */
  @Test
  fun `neverResolvedOnThisServer is false when a case evaluated to a non-null value`() {
    val coverage = CoverageTracker()
    coverage.record(CaseOutcome.EvaluatedNonNull)
    assertThat(coverage.neverResolvedOnThisServer()).isFalse()
  }

  /**
   * Builds the literal SQL call expression for one case of [signature] given [rawArguments] (one
   * literal per argument position, already present in [EDGE_VALUE_CORPUS] order) and
   * [argumentTypeNames] — the CONCRETE type to cast each literal to at the call site. Defaults to
   * [SafeFunctionSignature.argumentTypeNames] itself, which is correct for every ordinary,
   * non-pseudo-typed signature; a signature with a pseudo-type argument (e.g.
   * `cardinality(anyarray)`) passes the concrete instantiation actually under test for this case
   * (e.g. `integer[]`) instead, since a literal cannot be cast directly to a pseudo-type. Most
   * functions use ordinary `name(arg, arg, ...)` call syntax with each argument explicitly cast
   * (`$literal::$typeName`) so PostgreSQL resolves the EXACT overload under test. Three names need
   * special-casing because their only valid invocation form differs from an ordinary function
   * call:
   * - `ntile` is a window function (`prokind = 'w'`) — it cannot be called as an ordinary function,
   *   it requires a window context (`OVER ()`).
   * - `extract` is SQL-standard grammar, not an ordinary function call — `extract('hour'::text,
   *   x::time)` is a syntax error (`extract` cannot be followed by a parenthesized, comma-separated
   *   argument list the way a real function can); the only valid call form is `EXTRACT(<field>
   *   FROM <value>)`, with `<field>` a BARE keyword (`HOUR`, not `'hour'::text`).
   * - `position` is likewise SQL-standard grammar, not an ordinary function call —
   *   `position('a'::text, 'abc'::text)` is a syntax error; the only valid call form is
   *   `POSITION(<substring> IN <string>)`.
   *
   * Every other signature on every list uses ordinary call syntax; if a future entry needs
   * `substring ... from ... for ...`, `trim(leading/trailing/both ... from ...)`, or `overlay ...
   * placing ... from ...` syntax, it must be special-cased here the same way, or every one of its
   * cases will die on the same "never evaluated" syntax error `extract`/`position` did before being
   * special-cased.
   */
  private fun functionCallExpression(
    signature: SafeFunctionSignature,
    rawArguments: List<String>,
    argumentTypeNames: List<String> = signature.argumentTypeNames,
  ): String {
    val typedArguments = argumentTypeNames.zip(rawArguments)
      .joinToString(", ") { (typeName, literal) -> "$literal::$typeName" }
    return when (signature.name) {
      "ntile" -> "${signature.name}($typedArguments) OVER ()"
      "extract" -> {
        val fieldKeyword = rawArguments[0].trim('\'')
        val valueExpression = "${rawArguments[1]}::${argumentTypeNames[1]}"
        "EXTRACT($fieldKeyword FROM $valueExpression)"
      }

      "position" -> {
        val substringExpression = "${rawArguments[0]}::${argumentTypeNames[0]}"
        val stringExpression = "${rawArguments[1]}::${argumentTypeNames[1]}"
        "POSITION($substringExpression IN $stringExpression)"
      }

      else -> "${signature.name}($typedArguments)"
    }
  }

  /**
   * Executes `SELECT (<expression>) IS NULL` and returns the [CaseOutcome] describing what
   * happened. An actual `null` result is always recorded as a failure (naming [description] and
   * the exact [expression]) — that assertion is never weakened. A [SQLException] is only evidence
   * the call is total when it proves the call actually evaluated; see [isNotEvaluatedSqlState].
   */
  private fun checkTotal(
    stmt: Statement,
    description: String,
    expression: String,
    failures: MutableList<String>,
  ): CaseOutcome {
    val sql = "SELECT ($expression) IS NULL"
    return try {
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        if (rs.getBoolean(1)) {
          failures += "$description returned NULL for: $sql"
          CaseOutcome.EvaluatedNull
        } else {
          CaseOutcome.EvaluatedNonNull
        }
      }
    } catch (e: SQLException) {
      val message = "${e.sqlState}: ${e.message}"
      if (isNotEvaluatedSqlState(e.sqlState)) CaseOutcome.NotEvaluated(message) else CaseOutcome.EvaluatedError(message)
    }
  }

  /**
   * The mirror image of [checkTotal]: executes `SELECT (<expression>) IS NULL` and requires the
   * result to actually BE `null` — used by the [nonNullIffFirstArgumentNonNullFunctionOids] sweep's
   * property (ii), where a `NULL` first argument must poison the whole result regardless of every
   * other argument.
   *
   * Returns `true` ONLY when the case genuinely EVALUATED and CONFIRMED a `null` result — the one
   * outcome that actually proves the property, mirroring how only [CaseOutcome.EvaluatedNonNull]
   * proves totality for [checkTotal]. Returns `false` for a non-`null` result (also recorded as a
   * failure) and `null` for anything that did NOT confirm the property one way or the other: a
   * class-42 (never-evaluated) SQLSTATE, matching [isNotEvaluatedSqlState], AND a genuine runtime
   * error. An earlier version of this method conflated "evaluated to `null`" with "raised ANY
   * non-class-42 exception", returning `true` for both — a future entry whose every NULL-first case
   * happened to raise a real (non-class-42) runtime error, rather than actually returning `null`,
   * would then have passed this sweep having proven NOTHING about the property under test. The
   * caller must require at least one `true` case per signature, exactly the way
   * [CoverageTracker.requirePositiveCoverage] requires at least one [CaseOutcome.EvaluatedNonNull]
   * for [checkTotal] — a `false`/`null` case is not proof, the same way an [CaseOutcome.EvaluatedError]/
   * [CaseOutcome.NotEvaluated] case is not positive coverage there.
   */
  private fun checkAlwaysNull(
    stmt: Statement,
    description: String,
    expression: String,
    failures: MutableList<String>,
  ): Boolean? {
    val sql = "SELECT ($expression) IS NULL"
    return try {
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        if (rs.getBoolean(1)) {
          true
        } else {
          failures += "$description returned NON-NULL for: $sql"
          false
        }
      }
    } catch (_: SQLException) {
      null
    }
  }

  /**
   * Brute-force verification that every entry in [PgCatalogLoader.alwaysNonNullFunctionOids]
   * really is non-null for ANY combination of argument values, including when EVERY argument is
   * `NULL` — the exact claim [concat_ws] shipping on this list would have violated (`concat_ws`
   * returns `null` when its separator is `NULL`, even though every other argument is non-null).
   * This is the test the `concat_ws` bug this file's KDoc describes needed: unlike the totality
   * sweep above, which never substitutes an actual `NULL` literal for any argument,
   * [nullEveryPositionCases] specifically forces a `NULL` into every position, one at a time (with
   * every other position drawn from [EDGE_VALUE_CORPUS]), plus the all-`NULL` combination.
   *
   * OIDs are read from [PgCatalogLoader.alwaysNonNullFunctionOids] itself — computed live, the same
   * way production does — rather than a hardcoded OID, and resolved back to a `pg_proc.proname` via
   * [resolveProcName] so this test automatically covers whatever the production list actually
   * contains today. [NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME] supplies the CONCRETE arity/types to
   * call each name with, since `concat`'s single `pg_catalog` row is declared `VARIADIC "any"` — a
   * pseudo-type with no literal form of its own, unlike `anyarray`/`anyrange`/`anyelement`, which
   * [concreteInstantiationsFor] already knows how to instantiate.
   */
  @Test
  fun `every alwaysNonNullFunctionOids-listed function is non-null for every NULL-argument combination`() {
    val catalogLoader = PgCatalogLoader(connection)
    val oids = catalogLoader.alwaysNonNullFunctionOids
    assertThat(oids.isNotEmpty()).isTrue()
    val failures = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (oid in oids) {
        val name = resolveProcName(oid)
        val signatures = requireNotNull(NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME[name]) {
          "alwaysNonNullFunctionOids contains '$name' (oid=$oid) with no registered corpus signature in " +
            "NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME"
        }
        for (signature in signatures) {
          val description = "always-non-null function ${signature.name}(${signature.argumentTypeNames.joinToString(
            ",",
          )})"
          val coverage = CoverageTracker()
          for (rawArguments in nullEveryPositionCases(signature.argumentTypeNames)) {
            caseCount++
            coverage.record(checkTotal(stmt, description, functionCallExpression(signature, rawArguments), failures))
          }
          coverage.requirePositiveCoverage(description, failures)
        }
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(failures).isEmpty()
  }

  /**
   * Brute-force verification of BOTH directions of
   * [PgCatalogLoader.nonNullIffFirstArgumentNonNullFunctionOids]'s claim: (i) a non-null first
   * argument with `NULL`(s) anywhere else never produces a `null` result, and (ii) a `NULL` first
   * argument ALWAYS produces a `null` result, regardless of the other arguments. Property (ii) is
   * what distinguishes this list from [alwaysNonNullFunctionOids] — an entry here is non-null only
   * CONDITIONALLY, and a signature that turns out to be non-null even with a `NULL` first argument
   * belongs on that list instead, not this one.
   *
   * OIDs and corpus signatures are resolved the same way as the `alwaysNonNullFunctionOids` sweep
   * above — see that test's KDoc.
   */
  @Test
  fun `every nonNullIffFirstArgumentNonNullFunctionOids-listed function depends only on its first argument`() {
    val catalogLoader = PgCatalogLoader(connection)
    val oids = catalogLoader.nonNullIffFirstArgumentNonNullFunctionOids
    assertThat(oids.isNotEmpty()).isTrue()
    val failures = mutableListOf<String>()
    var caseCount = 0
    connection.createStatement().use { stmt ->
      for (oid in oids) {
        val name = resolveProcName(oid)
        val signatures = requireNotNull(NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME[name]) {
          "nonNullIffFirstArgumentNonNullFunctionOids contains '$name' (oid=$oid) with no registered corpus " +
            "signature in NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME"
        }
        for (signature in signatures) {
          val firstArgumentTypeName = signature.argumentTypeNames.first()
          val restArgumentTypeNames = signature.argumentTypeNames.drop(1)
          val signatureText = "${signature.name}(${signature.argumentTypeNames.joinToString(",")})"
          val fullDescription = "nonNullIffFirstArgument function $signatureText"

          // Property (i): non-null first argument, NULL(s) elsewhere -> never null.
          val nonNullFirstDescription = "$fullDescription with a non-null first argument"
          val nonNullFirstCoverage = CoverageTracker()
          for (firstLiteral in edgeValueCorpusFor(firstArgumentTypeName)) {
            for (restArguments in nullEveryPositionCases(restArgumentTypeNames)) {
              caseCount++
              val rawArguments = listOf(firstLiteral) + restArguments
              val expression = functionCallExpression(signature, rawArguments)
              nonNullFirstCoverage.record(checkTotal(stmt, nonNullFirstDescription, expression, failures))
            }
          }
          nonNullFirstCoverage.requirePositiveCoverage(nonNullFirstDescription, failures)

          // Property (ii): NULL first argument -> always null, regardless of the rest.
          val nullFirstDescription = "$fullDescription with a NULL first argument"
          var confirmedNullCaseCount = 0
          for (restArguments in cartesianProduct(restArgumentTypeNames.map { edgeValueCorpusFor(it) })) {
            caseCount++
            val rawArguments = listOf("NULL") + restArguments
            val expression = functionCallExpression(signature, rawArguments)
            if (checkAlwaysNull(stmt, nullFirstDescription, expression, failures) == true) confirmedNullCaseCount++
          }
          if (confirmedNullCaseCount == 0) {
            failures += "$nullFirstDescription has zero cases that evaluated to a confirmed NULL result"
          }
        }
      }
    }
    assertThat(caseCount).isGreaterThan(0)
    assertThat(failures).isEmpty()
  }

  /**
   * Verification of [PgCatalogLoader.lagLeadWithDefaultOids]'s claim: the 3-argument `lag`/`lead`
   * overloads are non-null when their value and default expressions are non-null, EVEN at a window
   * boundary where the 1- and 2-argument forms would return `null` (no such row exists to fetch).
   * Runs both `lag` and `lead` over a small non-null, ordered dataset with a non-null literal
   * default and asserts every row's output is non-null, then asserts the actual BOUNDARY row (the
   * one with no preceding/following row for the given offset) equals the literal default exactly —
   * proving the default genuinely filled in the boundary, not merely that no row happened to be
   * `null` for some other reason. Also asserts the 2-argument form is NOT on this list: it has no
   * default to fall back on, so it genuinely does return `null` at a window boundary.
   */
  @Test
  fun `lagLeadWithDefaultOids-listed 3-argument lag and lead fill window boundaries from a non-null default`() {
    val catalogLoader = PgCatalogLoader(connection)
    val threeArgumentOids = catalogLoader.lagLeadWithDefaultOids
    assertThat(threeArgumentOids.isNotEmpty()).isTrue()

    val actualThreeArgumentOids = connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE proname IN ('lag', 'lead') AND pronargs = 3 AND prokind = 'w'",
      ).use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } }
    }
    assertThat(threeArgumentOids).isEqualTo(actualThreeArgumentOids)

    val twoArgumentOids = connection.createStatement().use { stmt ->
      stmt.executeQuery(
        "SELECT oid::integer FROM pg_catalog.pg_proc WHERE proname IN ('lag', 'lead') AND pronargs = 2 AND prokind = 'w'",
      ).use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } }
    }
    assertThat(twoArgumentOids.isNotEmpty()).isTrue()
    assertThat(threeArgumentOids.intersect(twoArgumentOids)).isEqualTo(emptySet())

    connection.createStatement().use { stmt ->
      for (functionName in listOf("lag", "lead")) {
        val sql = """
          SELECT n, $functionName(v, 1, 'default') OVER (ORDER BY n) AS windowed
          FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(n, v)
          ORDER BY n
        """.trimIndent()
        stmt.executeQuery(sql).use { rs ->
          val windowedValues = mutableListOf<String?>()
          while (rs.next()) windowedValues += rs.getString("windowed")
          assertThat(windowedValues.size).isEqualTo(3)
          assertThat(windowedValues.any { it == null }).isFalse()
          val boundaryIndex = if (functionName == "lag") 0 else 2
          assertThat(windowedValues[boundaryIndex]).isEqualTo("default")
        }
      }
    }
  }

  companion object {
    private val pgVersion = System.getProperty("norm.test.pgVersion", "18")

    @JvmField
    @Container
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(
      DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
    ).apply {
      withDatabaseName("norm_test")
      waitingFor(
        WaitAllStrategy()
          .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
          .withStrategy(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(60)),
      )
    }

    private lateinit var connection: Connection

    /**
     * Field-name literals for `date_trunc`/`date_part`/`extract`'s first argument — a bare
     * generic text corpus (empty string, `'abc'`) would only ever error (invalid field name),
     * never exercising the field-dependent `null`-on-infinity behavior these functions are
     * documented (see [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES]) to special-case for only
     * SOME fields. Quoted, since `date_trunc`/`date_part` take the field name as an ordinary
     * `text` argument; [functionCallExpression] strips the quotes back off for `extract`, whose
     * SQL-standard grammar takes the field name as a bare keyword instead.
     */
    private val FIELD_NAME_CORPUS = listOf(
      "'hour'",
      "'day'",
      "'week'",
      "'quarter'",
      "'epoch'",
      "'microseconds'",
      "'timezone'",
      "'century'",
    )

    /** Timezone-name literals for `date_trunc`'s optional third argument. */
    private val TIMEZONE_NAME_CORPUS = listOf("'UTC'", "'America/New_York'")

    /**
     * Format-name literals for `encode`/`decode`'s second argument. `base64`/`hex`/`escape` are
     * PostgreSQL's only three recognized formats — without at least one of them in the corpus,
     * EVERY case dies on `unrecognized encoding: ""` (a genuine runtime error, but never a
     * successful non-null result), leaving the signature with zero positive coverage despite the
     * sweep having run dozens of cases against it. The empty-string entry is kept specifically so
     * an unrecognized-format case stays in the corpus too, landing in the "evaluated and errored"
     * bucket (see [isNotEvaluatedSqlState]) rather than being lost entirely.
     */
    private val FORMAT_NAME_CORPUS = listOf("'base64'", "'hex'", "'escape'", "''")

    /**
     * Hash-algorithm-name literals for pgcrypto's `digest`'s and `hmac`'s final argument. Mirrors
     * [FORMAT_NAME_CORPUS]'s reasoning: without a real algorithm name, every case dies on
     * `No such hash algorithm` with zero positive coverage. The empty-string entry keeps an
     * unrecognized-algorithm case in the corpus, landing in the "evaluated and errored" bucket.
     */
    private val HASH_ALGORITHM_NAME_CORPUS = listOf("'sha256'", "'md5'", "''")

    /**
     * Codepoint literals for `chr`'s single argument. `chr`'s min/max-`int4` edge values (`0`,
     * `-2147483648`, `2147483647`) ALL error (`null character not permitted` / `character number
     * must be positive` / `requested character too large for encoding`), so sweeping only
     * [EDGE_VALUE_CORPUS]'s plain `int4` corpus would leave `chr` with zero positive coverage
     * despite genuinely being total. `65` (`'A'`) and `1114111` (the maximum valid Unicode code
     * point) are real, in-range codepoints added specifically to give the sweep at least one
     * evaluated-non-null case; the original edge values are kept alongside them so an
     * out-of-range codepoint still lands in the "evaluated and errored" bucket.
     */
    private val CHR_CODEPOINT_CORPUS = listOf("65", "1114111", "0", "(-1)", "(-2147483648)", "2147483647")

    /**
     * Edge-case literal values (unquoted where bare numeric literals suffice, single-quoted
     * otherwise), keyed by `pg_type.typname`. Every literal is cast explicitly (`$literal::type`)
     * at the call site rather than left untyped, so PostgreSQL resolves the EXACT overload under
     * test instead of picking whichever overload its untyped-literal defaulting rules prefer.
     */
    private val EDGE_VALUE_CORPUS: Map<String, List<String>> = mapOf(
      // The minimum-integer literals are parenthesized because every call site embeds a corpus
      // entry as `$literal::$typeName` (`::` binds tighter than unary `-`), so an UNPARENTHESIZED
      // `-32768::int2` parses as `-(32768::int2)` — the INNER, still-positive literal overflows
      // int2 on its own coercion before the outer negation ever runs, raising a data-exception
      // SQLSTATE (class `22`) that [isNotEvaluatedSqlState] correctly treats as an evaluated PASS,
      // even though the minimum value never actually reached the entry under test. Parenthesizing
      // forces `(-32768)::int2` — negate the untyped literal FIRST, then coerce the already-signed
      // `-32768` to `int2`, which is in range. `-1` needs no parentheses: `-1::int2` parses as
      // `-(1::int2)`, and `1` is in range for every one of these types, so there is no overflow to
      // mask.
      "int2" to listOf("0", "-1", "32767", "(-32768)"),
      "int4" to listOf("0", "-1", "2147483647", "(-2147483648)"),
      "int8" to listOf("0", "-1", "9223372036854775807", "(-9223372036854775808)"),
      "numeric" to listOf("0", "-1", "'NaN'", "'Infinity'", "'-Infinity'"),
      "float4" to listOf("0", "-1", "'NaN'", "'Infinity'", "'-Infinity'"),
      "float8" to listOf("0", "-1", "'NaN'", "'Infinity'", "'-Infinity'"),
      "bool" to listOf("true", "false"),
      "text" to listOf("''", "'abc'", "'a%b_c'"),
      "bpchar" to listOf("''", "'a'"),
      "varchar" to listOf("''", "'a'"),
      "name" to listOf("''", "'a'", "'UTF8'"),
      "bytea" to listOf("'\\x'", "'\\x01'"),
      "bit" to listOf("'0'", "'1'"),
      "money" to listOf("'0'", "'-1'", "'92233720368547758.07'"),
      "date" to listOf("'2020-01-01'", "'infinity'", "'-infinity'"),
      "timestamp" to listOf("'2020-01-01'", "'infinity'", "'-infinity'"),
      "timestamptz" to listOf("'2020-01-01Z'", "'infinity'", "'-infinity'"),
      "time" to listOf("'00:00:00'", "'23:59:59.999999'"),
      "timetz" to listOf("'00:00:00+00'", "'23:59:59.999999+14'"),
      "interval" to listOf("'1 day'", "'infinity'", "'-infinity'", "'0'"),
      "uuid" to listOf("'00000000-0000-0000-0000-000000000000'"),
      "lseg" to listOf("'[(0,0),(1,1)]'"),
      // A CLOSED path and an OPEN path — the exact distinction that makes `path + path` unsafe.
      "path" to listOf("'((0,0),(1,1),(2,0))'", "'[(0,0),(1,1)]'"),
      "tsvector" to listOf("''", "'a b c'"),
      "integer[]" to listOf("'{}'", "'{1,2}'", "'{1,NULL}'"),
      "text[]" to listOf("'{}'", "'{a,b}'"),
      "int4range" to listOf("'empty'", "'[1,)'", "'(,)'", "'[1,5)'"),
      "numrange" to listOf("'empty'", "'[1,)'", "'(,)'"),
      "tsrange" to listOf("'empty'", "'(,)'"),
      "daterange" to listOf("'empty'", "'(,)'"),
      "int4multirange" to listOf("'{}'", "'{[1,5)}'"),
    )

    /**
     * pgcrypto's `digest`/`hmac` overloads, brute-force-swept for total-ness the same way as the
     * three static [PgCatalogLoader] safe lists, but NOT sourced from any of them: they are an
     * extension carve-out keyed through `pg_depend`, not a name/argument-type entry on a list (see
     * [PgCatalogLoader.loadNeverNullForNonNullInputOids]). Defined here, in the test, rather than
     * in production code, since nothing else needs a [SafeFunctionSignature] for them.
     */
    private val PGCRYPTO_FUNCTION_SIGNATURES = listOf(
      SafeFunctionSignature("digest", listOf("text", "text")),
      SafeFunctionSignature("digest", listOf("bytea", "text")),
      SafeFunctionSignature("hmac", listOf("text", "text", "text")),
      SafeFunctionSignature("hmac", listOf("bytea", "bytea", "text")),
    )

    /**
     * A self-cast (source type == target type, e.g. `varchar` -> `varchar`) with NO typmod on the
     * target folds away to a no-op: `EXPLAIN (VERBOSE) SELECT v::varchar FROM t` (`v` a `varchar`
     * column) shows bare `Output: v` — no cast node at all, and `pg_cast.castfunc` is never called.
     * These entries are licensed as typmod-ENFORCEMENT casts (see
     * [PgCatalogLoader.NEVER_NULL_CAST_SIGNATURES]'s KDoc), so sweeping them without a typmod would
     * grant coverage while never invoking the function the entry actually licenses. The target
     * side of every self-cast entry here therefore carries an explicit typmod shorter than at least
     * one of its literals, so the sweep exercises real truncation/rounding, not a no-op: `EXPLAIN
     * (VERBOSE) SELECT v::varchar(3) FROM t` shows `Output: (v)::character varying(3)`, a genuine
     * cast node.
     */
    private val SELF_CAST_TYPMOD_SUFFIX: Map<String, String> = mapOf(
      "numeric" to "(5,2)",
      "bpchar" to "(3)",
      "varchar" to "(3)",
      "timestamp" to "(0)",
      "timestamptz" to "(0)",
      "time" to "(0)",
      "timetz" to "(0)",
      "interval" to "(0)",
      "bit" to "(1)",
    )

    /**
     * Literal corpus for the TYPMOD form of each self-cast entry — a superset of
     * [EDGE_VALUE_CORPUS]'s plain corpus for the same source type, with at least one literal added
     * that is LONGER/more-precise than the typmod in [SELF_CAST_TYPMOD_SUFFIX], so truncation or
     * rounding is actually exercised (`'abcdef'::varchar(3)` truncates to `'abc'`;
     * `123.456::numeric(5,2)` rounds to `123.46`) rather than every case happening to already fit.
     */
    private val SELF_CAST_TYPMOD_LITERAL_CORPUS: Map<String, List<String>> = mapOf(
      "bpchar" to listOf("''", "'a'", "'abcdef'"),
      "varchar" to listOf("''", "'a'", "'abcdef'"),
      "numeric" to listOf("0", "-1", "'NaN'", "'Infinity'", "'-Infinity'", "123.456"),
      "timestamp" to listOf("'2020-01-01'", "'infinity'", "'-infinity'", "'2020-01-01 00:00:00.123456'"),
      "timestamptz" to listOf(
        "'2020-01-01Z'",
        "'infinity'",
        "'-infinity'",
        "'2020-01-01 00:00:00.123456Z'",
      ),
      "time" to listOf("'00:00:00'", "'23:59:59.999999'"),
      "timetz" to listOf("'00:00:00+00'", "'23:59:59.999999+14'"),
      "interval" to listOf("'1 day'", "'infinity'", "'-infinity'", "'0'", "'1.123456 seconds'"),
      "bit" to listOf("'0'", "'1'", "'101'"),
    )

    /**
     * Source-side typmod override for [SELF_CAST_TYPMOD_LITERAL_CORPUS] entries whose type's BARE
     * form (no typmod at all) does NOT mean "unconstrained" — unlike `varchar`/`numeric`/
     * `timestamp`/etc., whose bare form has no length/precision limit, bare `bit` means `bit(1)`
     * (the SQL-standard default length). Casting a literal straight to bare `bit` therefore
     * truncates it to a single bit BEFORE the self-cast under test ever runs, making every
     * source-side value length-1 and defeating the truncation [SELF_CAST_TYPMOD_SUFFIX]'s KDoc
     * describes this whole mechanism as existing to exercise: `('101'::bit)::bit(1)` would
     * truncate `'101'` down to `'1'` at the FIRST cast, so the outer, narrower `bit(1)` cast would
     * receive an already-1-bit value and never do any truncation of its own. This override casts
     * the source side to an explicit typmod wide enough to hold every literal in
     * `SELF_CAST_TYPMOD_LITERAL_CORPUS["bit"]` first (`bit(3)`), so the OUTER cast — to
     * [SELF_CAST_TYPMOD_SUFFIX]'s narrower `bit(1)` — is the one actually doing the truncation.
     */
    private val SELF_CAST_SOURCE_TYPMOD_SUFFIX: Map<String, String> = mapOf("bit" to "(3)")

    /**
     * Concrete arities/types to sweep for [PgCatalogLoader.alwaysNonNullFunctionOids]'s and
     * [PgCatalogLoader.nonNullIffFirstArgumentNonNullFunctionOids]'s NULL-argument properties,
     * keyed by `pg_proc.proname`. Both properties are keyed by NAME ALONE in production (see
     * [PgCatalogLoader.loadAlwaysNonNullFunctions]/
     * [PgCatalogLoader.loadNonNullIffFirstArgumentNonNullFunctionOids]'s `proname = '...'`
     * predicates), because `concat`/`concat_ws`'s single `pg_catalog` row for each is declared
     * `VARIADIC "any"`/`VARIADIC "any"` — the DECLARED argument type is the pseudo-type `any`
     * itself, with no literal form of its own, unlike `anyarray`/`anyrange`/`anyelement`, which
     * [concreteInstantiationsFor] already knows how to instantiate. This map supplies the concrete
     * arity/types actually exercised at the call site instead.
     *
     * `concat_ws` is registered here even though production correctly never lists it under
     * [PgCatalogLoader.alwaysNonNullFunctionOids] today — if that regressed (this is exactly the
     * shipped bug this whole file's KDoc describes), the always-non-null sweep must actually
     * EXERCISE `concat_ws`'s real NULL-argument behavior and fail on the genuine semantic violation
     * (`concat_ws(NULL, 'x', 'y')` returns `null`), not merely fail on a missing corpus
     * registration that would just as easily hide a real regression.
     */
    private val NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME: Map<String, List<SafeFunctionSignature>> = mapOf(
      "concat" to listOf(
        SafeFunctionSignature("concat", listOf("text", "text")),
        SafeFunctionSignature("concat", listOf("text", "text", "text")),
      ),
      "concat_ws" to listOf(
        SafeFunctionSignature("concat_ws", listOf("text", "text", "text")),
      ),
    )

    /**
     * Every argument combination needed to prove a function is non-null for ANY combination of
     * argument values INCLUDING when every argument is `NULL`: the all-`NULL` combination, plus,
     * for each argument position, that position forced to `NULL` with every OTHER position drawn
     * from the cartesian product of [EDGE_VALUE_CORPUS] for that position's type. `"NULL"` needs no
     * special handling from [functionCallExpression] — it is a valid raw argument literal the same
     * way `"'abc'"` or `"0"` is, since `$literal::$typeName` becomes plain `NULL::$typeName`.
     */
    private fun nullEveryPositionCases(argumentTypeNames: List<String>): List<List<String>> {
      val allNull = argumentTypeNames.map { "NULL" }
      val onePositionNullCases = argumentTypeNames.indices.flatMap { nullPosition ->
        val corpora = argumentTypeNames.mapIndexed { index, typeName ->
          if (index == nullPosition) listOf("NULL") else edgeValueCorpusFor(typeName)
        }
        cartesianProduct(corpora)
      }
      return listOf(allNull) + onePositionNullCases
    }

    /**
     * Resolves [oid] to its unqualified `pg_proc.proname` — lets the
     * `alwaysNonNullFunctionOids`/`nonNullIffFirstArgumentNonNullFunctionOids` sweeps look up which
     * concrete [NULL_ARGUMENT_SWEEP_SIGNATURES_BY_NAME] entry to test for an OID computed LIVE by
     * [PgCatalogLoader] (the same way production does), rather than hardcoding an OID that would
     * silently go stale across a PostgreSQL version bump.
     */
    private fun resolveProcName(oid: Int): String =
      connection.prepareStatement("SELECT proname FROM pg_catalog.pg_proc WHERE oid = ?").use { preparedStatement ->
        preparedStatement.setInt(1, oid)
        preparedStatement.executeQuery().use { rs ->
          check(rs.next()) { "No pg_proc row found for oid $oid" }
          rs.getString("proname")
        }
      }

    @JvmStatic
    @BeforeAll
    fun setup() {
      connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    }

    @JvmStatic
    @AfterAll
    fun teardown() {
      if (::connection.isInitialized) connection.close()
    }

    private fun argumentCorpusOverride(functionName: String, argumentIndex: Int): List<String>? = when {
      functionName in setOf("date_trunc", "date_part", "extract") && argumentIndex == 0 -> FIELD_NAME_CORPUS
      functionName == "date_trunc" && argumentIndex == 2 -> TIMEZONE_NAME_CORPUS
      functionName in setOf("encode", "decode") && argumentIndex == 1 -> FORMAT_NAME_CORPUS
      functionName == "chr" && argumentIndex == 0 -> CHR_CODEPOINT_CORPUS
      else -> null
    }

    private fun edgeValueCorpusFor(typeName: String): List<String> =
      requireNotNull(EDGE_VALUE_CORPUS[typeName]) { "No edge-value corpus registered for pg_type '$typeName'" }

    /**
     * The literal SQL cast expressions to sweep for [signature]. A self-cast entry (source ==
     * target, and present in [SELF_CAST_TYPMOD_SUFFIX]) is swept in its TYPMOD form — see
     * [SELF_CAST_TYPMOD_SUFFIX]'s KDoc for why the plain, no-typmod form would be a no-op. Every
     * other entry keeps the original bare `($literal::source)::target` form.
     */
    private fun castExpressionsFor(signature: SafeCastSignature): List<String> {
      val typmodSuffix = SELF_CAST_TYPMOD_SUFFIX[signature.sourceTypeName]
      return if (signature.sourceTypeName == signature.targetTypeName && typmodSuffix != null) {
        val literals = requireNotNull(SELF_CAST_TYPMOD_LITERAL_CORPUS[signature.sourceTypeName]) {
          "No typmod literal corpus registered for self-cast '${signature.sourceTypeName}'"
        }
        val sourceTypmodSuffix = SELF_CAST_SOURCE_TYPMOD_SUFFIX[signature.sourceTypeName] ?: ""
        literals.map { literal ->
          "($literal::${signature.sourceTypeName}$sourceTypmodSuffix)::${signature.targetTypeName}$typmodSuffix"
        }
      } else {
        edgeValueCorpusFor(signature.sourceTypeName).map { literal ->
          "($literal::${signature.sourceTypeName})::${signature.targetTypeName}"
        }
      }
    }

    /**
     * Concrete (type name, corpus) instantiations for [typeName]. A pseudo-type — `anyarray`,
     * `anyrange`, `anymultirange`, `anyelement` — instantiates to SEVERAL concrete types, because
     * a single generic `pg_operator` row (e.g. `anyrange && anyrange`) is shared by every
     * concrete range type at runtime; sweeping only one concrete instantiation would leave the
     * others unverified. A concrete type instantiates to itself.
     */
    private fun concreteInstantiationsFor(typeName: String): List<Pair<String, List<String>>> = when (typeName) {
      "anyarray" -> listOf("integer[]" to edgeValueCorpusFor("integer[]"), "text[]" to edgeValueCorpusFor("text[]"))
      "anyrange" -> listOf(
        "int4range" to edgeValueCorpusFor("int4range"),
        "numrange" to edgeValueCorpusFor("numrange"),
        "tsrange" to edgeValueCorpusFor("tsrange"),
        "daterange" to edgeValueCorpusFor("daterange"),
      )

      "anymultirange" -> listOf("int4multirange" to edgeValueCorpusFor("int4multirange"))
      "anyelement" -> listOf("int4" to edgeValueCorpusFor("int4"))
      else -> listOf(typeName to edgeValueCorpusFor(typeName))
    }

    /**
     * The cartesian product of [lists] — e.g. `[[a, b], [x, y]]` becomes `[[a, x], [a, y], [b,
     * x], [b, y]]`. An empty input list of lists (a zero-argument signature, e.g. `now()`)
     * produces a single empty combination, so the caller still runs exactly one case.
     */
    private fun cartesianProduct(lists: List<List<String>>): List<List<String>> =
      lists.fold(listOf(emptyList())) { accumulated, list ->
        accumulated.flatMap { combination -> list.map { combination + it } }
      }

    /**
     * The cartesian product of per-position [concreteInstantiationsFor] results — e.g. a
     * two-argument signature whose first position is `anyarray` (two instantiations,
     * `integer[]`/`text[]`) and second position is `text` (one instantiation, itself) becomes two
     * combinations: `[integer[], text]` and `[text[], text]`. An empty [positionInstantiations]
     * (a zero-argument signature, e.g. `now()`) produces a single empty combination, exactly like
     * [cartesianProduct] does for the same case.
     */
    private fun cartesianProductOfInstantiations(
      positionInstantiations: List<List<Pair<String, List<String>>>>,
    ): List<List<Pair<String, List<String>>>> =
      positionInstantiations.fold(listOf(emptyList())) { accumulated, instantiationsForPosition ->
        accumulated.flatMap { combination -> instantiationsForPosition.map { combination + it } }
      }

    /**
     * `true` when [signature] resolves to a real `pg_proc` row on this connected server, checked
     * by the SAME lookup [PgCatalogLoader.loadNeverNullForNonNullInputOids] performs in
     * production (name plus the exact ordered list of declared argument `pg_type.typname`
     * values, restricted to `pronamespace = 'pg_catalog'`) — just run per-signature here instead
     * of batched. This is the independent check [neverResolvedOnThisServer]'s KDoc says every
     * caller must cross-check its skip set against, since [CoverageTracker.neverResolvedOnThisServer]
     * alone cannot distinguish "does not resolve here" from "resolves here but the swept call
     * form doesn't match its grammar" (see that KDoc's `position` counterexample).
     */
    private fun functionResolvesOnThisServer(signature: SafeFunctionSignature): Boolean {
      val sql = """
        SELECT EXISTS (
          SELECT 1
          FROM pg_catalog.pg_proc p
          JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'pg_catalog'
            AND p.proname = ?
            AND COALESCE(
              (
                SELECT array_agg(t.typname::text ORDER BY u.ordinality)
                FROM unnest(p.proargtypes) WITH ORDINALITY AS u(type_oid, ordinality)
                JOIN pg_catalog.pg_type t ON t.oid = u.type_oid
              ),
              ARRAY[]::text[]
            ) = ?
        )
      """.trimIndent()
      return connection.prepareStatement(sql).use { preparedStatement ->
        preparedStatement.setString(1, signature.name)
        preparedStatement.setArray(2, connection.createArrayOf("text", signature.argumentTypeNames.toTypedArray()))
        preparedStatement.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean(1)
        }
      }
    }

    /**
     * `true` when [signature] resolves to a real, `castfunc`-backed `pg_cast` row on this
     * connected server — the cast analogue of [functionResolvesOnThisServer], mirroring the same
     * `pg_cast`/`pg_type`/`pg_proc`/`pg_namespace` lookup
     * [PgCatalogLoader.loadNeverNullForNonNullInputOids] performs in production.
     */
    private fun castResolvesOnThisServer(signature: SafeCastSignature): Boolean {
      val sql = """
        SELECT EXISTS (
          SELECT 1
          FROM pg_catalog.pg_cast c
          JOIN pg_catalog.pg_type st ON st.oid = c.castsource
          JOIN pg_catalog.pg_type tt ON tt.oid = c.casttarget
          JOIN pg_catalog.pg_proc p ON p.oid = c.castfunc
          JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
          WHERE c.castfunc != 0
            AND n.nspname = 'pg_catalog'
            AND st.typname = ?
            AND tt.typname = ?
        )
      """.trimIndent()
      return connection.prepareStatement(sql).use { preparedStatement ->
        preparedStatement.setString(1, signature.sourceTypeName)
        preparedStatement.setString(2, signature.targetTypeName)
        preparedStatement.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean(1)
        }
      }
    }

    /**
     * `true` when [signature] resolves to a real, `oprcode`-backed `pg_operator` row on this
     * connected server — the operator analogue of [functionResolvesOnThisServer], mirroring the
     * same `pg_operator`/`pg_type`/`pg_namespace` lookup
     * [PgCatalogLoader.loadNeverNullForNonNullInputOids] performs in production. A `null`
     * [SafeOperatorSignature.leftTypeName]/[SafeOperatorSignature.rightTypeName] means the
     * operator has no operand on that side (a prefix/postfix operator), matched with `IS NOT
     * DISTINCT FROM` the same way production's bulk query does.
     */
    private fun operatorResolvesOnThisServer(signature: SafeOperatorSignature): Boolean {
      val sql = """
        SELECT EXISTS (
          SELECT 1
          FROM pg_catalog.pg_operator o
          JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
          LEFT JOIN pg_catalog.pg_type lt ON lt.oid = o.oprleft
          LEFT JOIN pg_catalog.pg_type rt ON rt.oid = o.oprright
          WHERE n.nspname = 'pg_catalog'
            AND o.oprcode != 0
            AND o.oprname = ?
            AND lt.typname IS NOT DISTINCT FROM ?
            AND rt.typname IS NOT DISTINCT FROM ?
        )
      """.trimIndent()
      return connection.prepareStatement(sql).use { preparedStatement ->
        preparedStatement.setString(1, signature.symbol)
        preparedStatement.setString(2, signature.leftTypeName)
        preparedStatement.setString(3, signature.rightTypeName)
        preparedStatement.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean(1)
        }
      }
    }

    /**
     * `true` when [sqlState] is a PostgreSQL SQLSTATE whose class means the call never actually
     * evaluated — class `42` ("Syntax Error or Access Rule Violation"), which covers a genuine
     * syntax error (`42601`), an undefined function for the given argument types (`42883`), and a
     * cannot-coerce type mismatch (`42846`) alike. A `SQLException` in this class is not evidence
     * the underlying function/cast/operator is total; the call never got far enough to say
     * anything about that. Every other class (a data exception, an out-of-range value, a
     * feature-not-supported unit, ...) means the call DID reach the function's body and it chose
     * to raise rather than return — which IS evidence of total-ness, so it counts as a pass (see
     * [checkTotal]) even though it is not POSITIVE coverage (see [CoverageTracker]).
     */
    private fun isNotEvaluatedSqlState(sqlState: String?): Boolean = sqlState != null && sqlState.take(2) == "42"
  }
}

/**
 * The outcome of one [SafeListSweepTest.checkTotal] case.
 */
private sealed interface CaseOutcome {
  /** The call evaluated and returned a genuine non-null value — the only outcome that licenses total-ness. */
  data object EvaluatedNonNull : CaseOutcome

  /** The call evaluated and returned `null` — an actual failure, already recorded by [SafeListSweepTest.checkTotal]. */
  data object EvaluatedNull : CaseOutcome

  /** The call evaluated and then raised a genuine runtime error — a pass, but not positive coverage. */
  data class EvaluatedError(val message: String) : CaseOutcome

  /** The call never evaluated at all (syntax error / undefined function / cannot-coerce) — not a pass. */
  data class NotEvaluated(val message: String) : CaseOutcome
}

/**
 * Accumulates [CaseOutcome]s for one safe-listed signature and asserts, once every case for that
 * signature has been recorded, that at least [minimumPositiveCases] of them actually evaluated to
 * a non-null value. A signature whose cases are all errors (or, worse, all never-evaluated) tells
 * the sweep nothing about whether the signature is really total — see this file's KDoc.
 */
private class CoverageTracker(private val minimumPositiveCases: Int = 1) {
  private var evaluatedNonNullCount = 0
  private var evaluatedErrorCount = 0
  private var notEvaluatedCount = 0
  private val nonPositiveMessages = sortedSetOf<String>()

  fun record(outcome: CaseOutcome) {
    when (outcome) {
      CaseOutcome.EvaluatedNonNull -> evaluatedNonNullCount++
      CaseOutcome.EvaluatedNull -> Unit
      is CaseOutcome.EvaluatedError -> {
        evaluatedErrorCount++
        nonPositiveMessages += outcome.message
      }

      is CaseOutcome.NotEvaluated -> {
        notEvaluatedCount++
        nonPositiveMessages += outcome.message
      }
    }
  }

  /**
   * `true` when EVERY recorded case was [CaseOutcome.NotEvaluated] — i.e. every single case died
   * on a class-`42` syntax-error/undefined-function/cannot-coerce SQLSTATE (see
   * [SafeListSweepTest.isNotEvaluatedSqlState]) — and at least one case was recorded at all.
   *
   * This is offered as EVIDENCE that a signature does not resolve on this connected server at
   * all — e.g. `int2->bytea`, a cast added in PostgreSQL 18, on a PostgreSQL 16 or 17 server —
   * never as PROOF on its own. Class-42 does NOT depend only on the signature's TYPES; it also
   * depends on the swept call's FORM, which can be unparseable even for a signature that
   * genuinely resolves here. `position(text, text)` is the live counterexample: it resolves fine
   * in `pg_proc` and ships on [PgCatalogLoader.NEVER_NULL_FUNCTION_SIGNATURES], yet the ordinary
   * function-call form `position('a'::text, 'abc'::text)` raises `42601: syntax error at or near
   * ","`, because `position`'s only valid invocation is the SQL-standard `POSITION(<substring>
   * IN <string>)` grammar, not a parenthesized comma-separated argument list.
   * [SafeListSweepTest.functionCallExpression] special-cases `position` (and `extract`) to their
   * correct grammar specifically to dodge this; a FUTURE entry needing similar SQL-standard
   * grammar (`substring ... from ...`, `trim(both ... from ...)`, `overlay ... placing ... from
   * ...`) that is not special-cased the same way would look, to this method alone, exactly like a
   * genuine version gap — every one of its cases would die the same all-class-42 way, even though
   * it resolves here.
   *
   * That is why no caller trusts this method's result by itself: every test that calls it also
   * cross-checks the resulting skip set against an INDEPENDENT catalog-only resolution check
   * (`functionResolvesOnThisServer`/`castResolvesOnThisServer`/`operatorResolvesOnThisServer`,
   * the same `pg_proc`/`pg_cast`/`pg_operator` lookup
   * [PgCatalogLoader.loadNeverNullForNonNullInputOids] performs in production, just run
   * per-signature) and asserts the two sets are equal. A signature this method calls a skip that
   * the catalog says DOES resolve is then a loud assertion failure — exactly the case a future
   * un-special-cased grammar-only entry would produce, closing the gap a bare `println` of the
   * skip set left open.
   *
   * A signature with at least one [CaseOutcome.EvaluatedError] alongside its
   * [CaseOutcome.NotEvaluated] cases (or none at all) is NOT covered by this method — that mix is
   * impossible for a genuinely non-resolving signature, so it is left to [requirePositiveCoverage]
   * to fail normally, exactly as it did before this method existed.
   */
  fun neverResolvedOnThisServer(): Boolean =
    evaluatedNonNullCount == 0 && evaluatedErrorCount == 0 && notEvaluatedCount > 0

  fun requirePositiveCoverage(description: String, failures: MutableList<String>) {
    if (evaluatedNonNullCount < minimumPositiveCases) {
      failures += "$description has zero cases that evaluated to a non-null value " +
        "(observed: ${nonPositiveMessages.joinToString("; ")})"
    }
  }
}
