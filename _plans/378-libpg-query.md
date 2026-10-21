# #378 — Investigate libpg_query to replace hand-written SQL text scanning

Status: FROZEN 2026-10-15 (fable-advisor; C1, C2, C6 replaced).

Branch: `spike/378-libpg-query` from `main` (`7c25f92`).

## User request (verbatim)

"go ahead with the #378 investigation"

## Issue text the criteria derive from (#378)

- Q1: "Is there a maintained JVM binding, or would Norm need its own JNI/JNA/FFM layer and per-platform native
  binaries inside the Gradle plugin? What does that cost in plugin size, supported platforms, and classloader
  isolation (the plugin already shades its dependencies)?"
- Q2: "Which PostgreSQL grammar versions does it track, and how does that interact with Norm running against
  PG 16–18?"
- Q3: "Most nodes record only a start offset. Can every boundary Norm needs (the end of a select item, the start
  of the `FROM`/`RETURNING` keyword, CTE body extents, placeholder positions) be derived from the tree, or are some
  still text-dependent?"
- Q4: "Which of the files listed above would it remove completely, and which would remain?" Files: `SqlLexer.kt`,
  `SqlKeywordScanner.kt`, `SqlOutputClause.kt`, `SqlCteClause.kt`, `SqlStarItem.kt`, `SqlParameterInferrer.kt`,
  `SqlPlaceholders.kt`, `QueryFileParser.kt`.
- Deliverable: "A written recommendation (go / no-go) that answers the four questions with evidence, plus a
  prototype showing `parseSelectItems` on libpg_query passing the existing `SqlOutputClauseTest` suite. This is not
  a migration."

## Lead candidate (unverified premises)

- P1: libpg_query compiled to WASI WebAssembly runs on Chicory (pure-JVM runtime), so no JNI/natives. Evidence so
  far: wasilibs/go-pgquery runs a WASI build of libpg_query on wazero. Unverified on Chicory.
- P2: Norm's `?` placeholders are not valid PostgreSQL grammar; libpg_query may reject them. Unverified.

## Acceptance criteria

C1 (Q1). `_plans/378-recommendation.md` states whether a maintained JVM binding exists (Maven Central + GitHub
search; name what was found or that nothing was) and which integration route the prototype uses. It reports, with
the measuring command and output: the on-disk size of the artifacts a migration would add to the `gradle-plugin`
shadow jar (runtime jars from the Gradle cache plus the `.wasm`/native binary); the platforms it runs on (WASM
route: "any JDK 25 platform", shown by the prototype test passing under the build toolchain
`buildSrc/src/main/kotlin/com/pkware/gradle/JavaConventionsPlugin.kt:26`; native route: per-platform binaries
listed); and whether the dependency must join the `shaded` configuration (`gradle-plugin/build.gradle.kts:21-28`)
and why.

C2 (Q2). Names the libpg_query version and PG grammar the prototype uses, cites its README/branch scheme for
version-to-PG-major mapping, and reports whether it parses the PG 18 input already at `SqlOutputClauseTest.kt:137`
(`RETURNING WITH (OLD AS o, NEW AS n) ...`) — accepted or the exact error — and states the consequence for a user
on PG 16 vs 18.

C3 (Q3). For each boundary named in Q3 — end of a select item, start of `FROM` keyword, start of `RETURNING`
keyword, CTE body extent, placeholder positions — the recommendation states derivable-from-tree or text-dependent,
backed by actual prototype parse output (node + `location`) for an example SQL. It states how Norm's `?`
placeholders are handled (accepted, rejected, or rewritten before parsing) with evidence.

C4 (Q4). For each of the eight listed files, the recommendation states removed completely / partly / remains, with
the specific responsibility that keeps any surviving part alive.

C5. The recommendation opens with a one-line go / no-go and the conditions it depends on.

C6 (prototype). A libpg_query-backed `parseSelectItems(sql: String): List<SelectItem>` exists in
`generator/src/test/kotlin/norm/generator/` (test source set only). Production `SqlOutputClause.kt`, its callers,
and `SqlOutputClauseTest.kt` are byte-for-byte unchanged. A new test class is produced by copying
`SqlOutputClauseTest.kt` with exactly these edits: class renamed; every `parseSelectItems(` call redirected to the
prototype; the tests calling `parseOutputItemsWithAlias` removed (`:499-506`, `:1586-1593`, nested
`ExplicitAliasToken` `:1597-1629`); the two top-level data classes at `:18` and `:23` removed from the copy (already
declared). The verifier confirms by diff that no assertion changed. The prototype returns an empty list when
libpg_query reports a parse error. The recommendation reports, from the JUnit XML: tests run, passed, and two
failure lists — (a) "PostgreSQL rejects this input": each confirmed by executing the input against the PG 18
testcontainer and quoting the server error; (b) real failures: input, expected, actual. The prototype is not
claimed to pass while list (b) is non-empty.

## Rulings (fable-advisor)

R1. Copy the suite, do not parameterize: `:504` sits inside `ParseSelectItems` but calls
`parseOutputItemsWithAlias`; an injected-function subclass would silently run production code for it.

R2. Route: Chicory (`com.dylibso.chicory:runtime` + `wasi`, in `gradle/libs.versions.toml`, generator
`testImplementation`) loading the WASI build of libpg_query. Parse output JSON or protobuf, whichever the module
exports; a JSON/protobuf library is a test-only catalog addition. First step is a `SELECT 1` smoke test settling P1
(instantiates on WASI preview1; PG's setjmp/longjmp error path works). If it fails after one attempt at supplying
missing imports, fall back to FFM (`java.lang.foreign`) against `libpg_query` built locally with `make`; the
recommendation then records natives as the Q1 cost. Do not build on an existing JVM binding unless the C1 search
finds one tracking libpg_query 17+ released in the last year.

R3. Commit the prebuilt `.wasm` from wasilibs/go-pgquery at a pinned commit into `generator/src/test/resources/`;
record URL, commit, libpg_query version, and `shasum -a 256` in the recommendation. Do not build with
Docker/wasi-sdk; name go-pgquery's build Dockerfile as the migration's reproduction route.

R4. Tests run concurrently (`JavaConventionsPlugin.kt:46-47`). One Chicory `Instance` has one linear memory:
instantiate per call or serialize behind a lock.

## Out of scope

- Replacing any production caller, deleting any scanner file, regenerating golden files.
- The other seven files' prototypes.
- Posting to GitHub.

## Verification

- `./gradlew :generator:test --tests '<prototype test class>' --rerun` passes; quote elapsed time.
- `./gradlew :generator:check` passes.
- Production `SqlOutputClauseTest` still passes unchanged.

## Amendment 2026-10-19 (user-approved)

R3 amended: also build libpg_query `18.1.0` to WASI with go-pgquery's `buildtools/wasm/Dockerfile` (commit
`1d929f3`), substituting libpg_query 18.1.0 C sources for `internal/cparser`. Replace the committed PG 17 module with
the PG 18 build, rerun the copied suite, and update C1/C2/C6 figures in `_plans/378-recommendation.md`. If
`patch.txt` does not apply or the build fails, record the exact error and keep the PG 17 module.
