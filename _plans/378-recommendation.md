# #378 — libpg_query recommendation

**Conditional go.** Adopt libpg_query for the parse-tree work only if (1) Norm ships a PG 18 build of the WASI module (met by this spike: libpg_query `18.1.0` built locally, see Q1; it accepts `RETURNING WITH (OLD AS o, NEW AS n)`), (2) Norm keeps a small token-level layer for item extents and `?` placeholders, and (3) the parse cost on Chicory's interpreter (about 3 ms per parse) is accepted or removed with Chicory's AOT compiler (not measured). Condition (1) also commits Norm to maintaining the build: go-pgquery publishes no PG 18 module and its `internal/cparser` needs seven edited files to build on WASI (Q1).

Everything below was measured on branch `spike/378-libpg-query` in `generator/src/test/` only. Production code is unchanged. Items marked **unverified** have no evidence from this spike.

## Q1 — binding, route, cost (C1)

Search of existing bindings:

- Maven Central (`search.maven.org/solrsearch`, queries `pg_query`, `libpg_query`, `pgquery`, `libpgquery`, `pg-query`, `a:pg_query*`, `g:com.pganalyze`): `numFound 0` for each query that returned JSON. The queries `a:*pgquery*`, `a:*libpg*`, `pganalyze` and `g:io.github.pganalyze` returned no JSON (wildcard queries unsupported or timed out). No binding is on Maven Central.
- GitHub repository search (`libpg_query` with Java/Kotlin/Scala/JNI/JNA/Panama/Chicory, `pg_query java`): `groestl/pg_query_java` (1 star, JNI, JitPack only, tag `v5.2.0`, Linux and macOS on x86_64/aarch64), `hannosgit/pg_queryj` (0 stars, no releases), `feixiangdexiaozhidan/pgquery-jni` (0 stars, no releases), `vbergeron/pgquery-scala` (1 star, PG 15-17, last push 2024-12-11). None is maintained or published to Maven Central, and none tracks libpg_query 17+ with a release in the last year, so R2 forbids building on one.

Route used: Chicory 1.7.5 (`com.dylibso.chicory:runtime`, `wasi`; `wasm` and `log` arrive transitively) running a WASI build of libpg_query `18.1.0` (PG 18.6), committed as `generator/src/test/resources/libpg_query.wasm`. The build uses the Dockerfile of `wasilibs/go-pgquery` at commit `1d929f3206792739f6874ab540c51cc72053bc13` (2026-10-13), `buildtools/wasm/Dockerfile` (wasi-sdk 34, binaryen 125, `wasm32-wasip1-threads`).

- Committed module: sha256 `2a3430dcf5e3096eddb3e454f009e7b04a45854501481f6ddfb767ece73b0cf1`, 2,669,794 bytes (`shasum -a 256`, `ls -l`).
- The PG 17 module from the same repo (`internal/wasm/libpg_query.so`, sha256 `8cdc5236e2f89d1daef3728297ecf028cd68ef6fe2c6a4bfebf8094588b8c83b`) was the first module in this spike. Rebuilding go-pgquery unmodified with `docker build -f buildtools/wasm/Dockerfile -t pgq-wasm17 .` on this machine (aarch64 Docker server, `uname -m` in the container `aarch64`, 21 min including the 400 MB base image pull) produced a `libpg_query.so` with the same sha256, `8cdc5236...8c83b`. The build is therefore reproducible, and an aarch64 host gives the same bytes as the committed build.
- How `internal/cparser` is populated: it is the flattened `parser/` directory of `pganalyze/pg_query_go` tag `v6.2.2` (libpg_query `17-6.2.2`; `pg_query_go`'s `Makefile` target `update_source` does the flattening) plus hand edits for WASI. `diff -rq` of the two directories lists seven edited files, none of which is in `buildtools/wasm/patch.txt`; `patch.txt` only touches `elog.h`, `elog.c` and `strerror.c`. The wasilibs `tools` repo has no task that populates it.
- Build of 18.1.0: libpg_query `18.1.0` (commit `c1546e7e97edc93fe8474d7a59881e91fbf48cfc`) was flattened into the same layout by hand (`src/*`, `src/postgres/*`, `src/postgres/include` to `include/postgres`, `src/include/*`, `pg_query.h`, `postgres_deparse.h`, `pg_query_scan_tokens.h`, `protobuf/*.h`, `vendor/xxhash`). Release 18 replaces protobuf-c with upb, so `vendor/upb` and `protobuf/pg_query.upb_minitable.c` go in as well. `patch.txt` applied to this tree unchanged (`patch -p3 --dry-run` printed the three file names with no fuzz or offset messages). The Dockerfile needed one change: `-Iinclude/upb -Iinclude/upb/third_party/utf8_range` added to the compile command. The WASI edits needed seven files, in `_plans/378-wasm18-sources.patch` (7 files, 44 insertions, 9 deletions): `pg_config.h` (`HAVE_SYSLOG` off), `pgstat.h` (`backend_status.h` not included on WASI, `miscadmin.h` included), `error_assert.c` (`getpid` replaced by a constant), `error_elog.c` and `init_globals.c` (`libpq` and `common/ip.h` includes dropped), and `guc_tables.c` and `tcop_postgres.c` (each replaced under `#ifdef __wasi__` by stubs: `check_function_bodies`, `log_min_messages` and `backtrace_functions`; `debug_query_string`, `whereToSendOutput` and an empty `ProcessInterrupts`). The 17 `tcop_postgres.c` stub also defines `max_stack_depth`, `check_stack_depth` and `stack_is_too_deep`; in 18 these come from `src_backend_utils_misc_stack_depth.c`. Compiling the unedited 18 tree failed with `fatal error: 'netdb.h' file not found` (reached through `libpq/pqcomm.h`, `common/ip.h` and `replication/walreceiver.h`) and `'syslog.h' file not found`; with the seven edits the compile, `wasm-ld` link and `wasm-opt` step all succeeded (`build18.log`: no `error:` lines, `docker build` exit 0).
- Output extraction: `docker run -v <scratchpad>/out:/out` left the directory empty (the macOS temp directory is not shared into the colima VM), so the files were taken with `docker create` and `docker cp <id>:/workspace/libpg_query.so`.
- Module shape, from `wasm-objdump -x libpg_query.so` in the build image (the 17 module was checked earlier with Chicory's `Parser.parse` in a scratch program): the 18 module imports exactly `wasi_snapshot_preview1.{fd_close,fd_fdstat_get,fd_read,fd_seek,fd_write,proc_exit,sched_yield,random_get}` and a shared `env.memory` (initial 32 pages, the 17 module had 34). Exports `malloc`, `free`, `pg_query_init`, `pg_query_parse`, `pg_query_free_parse_result`, `pg_query_parse_protobuf`, `pg_query_scan`, `pg_query_free_scan_result`, `_initialize`, `__wasm_init_tls`, `__stack_pointer`, `__tls_size`, `__tls_align` and others. The calling convention is copied from go-pgquery `parser/parser_wazero.go` (`pgQueryParse`: 12-byte result struct, error pointer at offset 8; `pgQueryScanProtobuf`: 16-byte struct, error pointer at offset 12).
- The shared memory is supplied by `ByteBufferMemory(MemoryLimits(32, 65536, true))` (34 for the PG 17 module), so go-pgquery's separate `memory.wasm` is not needed.

Smoke test (R2 step 1), `LibPgQuerySmokeTest`, passes on Chicory with no supplied imports beyond WASI preview 1:

```
SMOKE parse SELECT 1 => {"version":180006,"stmts":[{"stmt":{"SelectStmt":{"targetList":[{"ResTarget":{"val":{"A_Const":{"ival":{"ival":1},"location":7}},"location":7}}],...
```

The syntax-error input `SELEC 1` raises `LibPgQueryError` with message `syntax error at or near "SELEC"`, and the next call on the same instance succeeds. PG's setjmp/longjmp error path therefore works on Chicory. P1 is confirmed.

Sizes, measured:

| Artifact | Bytes |
| --- | --- |
| `chicory runtime-1.7.5.jar` | 151,620 |
| `chicory wasi-1.7.5.jar` | 59,497 |
| `chicory wasm-1.7.5.jar` | 213,420 |
| `chicory log-1.7.5.jar` | 9,419 |
| `libpg_query.wasm` (PG 18) | 2,669,794 (499,417 with `gzip -9`) |
| `gson-2.14.0.jar` (only if a JSON reader is kept) | 313,604 |
| Total added, without gson | 3,103,750 |

Source command: `find ~/.gradle/caches/modules-2 -name '*.jar' -path '*dylibso*' ! -name '*sources*' | xargs ls -l` and `ls -l generator/src/test/resources/libpg_query.wasm`. The current shadow jar, from `./gradlew :gradle-plugin:shadowJar`, is `gradle-plugin/build/libs/gradle-plugin-0.6.1-SNAPSHOT.jar` at 33,377,880 bytes, so the addition is about 9.3 percent before compression (3,103,750 / 33,377,880) and about 2.8 percent if the wasm deflates like `gzip -9` ((433,956 + 499,417) / 33,377,880). The PG 17 module was 2,488,452 bytes (8.8 and 2.6 percent).

Platforms: any platform with the build toolchain's JDK 25 (`buildSrc/src/main/kotlin/com/pkware/gradle/JavaConventionsPlugin.kt:26`). The prototype tests ran under that toolchain (`/Library/Java/JavaVirtualMachines/temurin-25.jdk` in the Gradle test worker command line) on macOS arm64. Only macOS arm64 was run: Linux and Windows are **unverified**, but nothing in the route is platform specific. A native route would need a per-platform binary for each of Linux, macOS and Windows on x86_64 and aarch64 and a JNI/FFM loader; it was not built.

Shading (`gradle-plugin/build.gradle.kts:21-28`): the Chicory jars must join the `shaded` configuration. The plugin shades what generator needs at runtime because Gradle's isolated plugin classloader hides other plugin jars (the memory note on Wire and the comment above `tasks.shadowJar` describe the same failure for Testcontainers and KotlinPoet). The generator is already a `shaded` dependency (`shaded(projects.generator)`), so a Chicory `implementation` dependency of the generator is pulled in transitively. Whether relocation of `com.dylibso.chicory` is needed is **unverified**; Chicory loads the wasm with `getResourceAsStream`, and relocation does not move resources. The wasm resource must be in the generator's `main` resources, not `test`.

Concurrency (R4): `LibPgQuery` holds one `Instance` behind a lock and discards it after a trap. `LibPgQueryConcurrencyTest` runs 400 calls on 8 threads and compares each with the hand-written parser's result; it passes.

Performance, from `LibPgQueryTimingTest` run with `RejectedInputsTest` on the PG 18 module (Chicory interpreter, so noisy; the PG 17 module measured 493 ms cold and parse-only times of 3111, 2543 and 4721 us):

```
TIMING cold first parse: 564 ms
TIMING SELECT id, name AS n, COUNT(*) FROM users WHERE a = 1 => prototype 7171 us, parse only 3087 us, hand-written 50 us
TIMING UPDATE t SET x = 1 RETURNING id, name, upper(name) AS up => prototype 12626 us, parse only 2818 us, hand-written 10 us
TIMING WITH c AS (SELECT 1 AS a) SELECT a, a + 1 AS b, CASE WHEN a > 1 THEN 'x' ELSE 'y' END AS z FROM c => prototype 37013 us, parse only 4984 us, hand-written 36 us
```

The prototype takes 143, 1,263 and 1,028 times as long per call as the hand-written parser on the three queries (7,171/50, 12,626/10 and 37,013/36 µs). The hand-written times are single-digit to double-digit microseconds and vary between runs, so the ratios are rough. The last select item needs one re-parse per candidate token (see Q3), which explains the gap between "prototype" and "parse only". The cost is acceptable at code-generation time for tens of queries; whether it is acceptable for large query files is a product decision. Chicory's AOT compiler (`com.dylibso.chicory:compiler`) is **unverified**.

## Q2 — grammar versions (C2)

- Prototype module: libpg_query `18.1.0` for PG 18 (`"version":180006` in every parse result, from `LibPgQuerySmokeTest` output; `PG_VERSION "18.6"` and `PG_VERSION_NUM 180006` in `pg_query.h:214-215` of the tag; the libpg_query `Makefile` sets `PG_VERSION = 18.6`). The first module in this spike was PG 17 (`"version":170007`, go-pgquery `v6.2.2` sources).
- libpg_query README, "Versions" section: "Each major version is maintained in a dedicated git branch", with `18-latest` (active development), `17-latest` (critical fixes only), `16-latest` and older (no longer supported). Tags are `<PG major>-<libpg_query version>` through `17-6.2.5`; PG 18 tags are `18.0.0` (2026-05-21) and `18.1.0` (2026-09-30), whose parse result carries `"version": 180006` in the README example.
- go-pgquery has no PG 18 build: its latest commits (2026-10-13) are dependency bumps on `pg_query_go v6.2.5`, and `pg_query_go` has no PG 18 tag (`git ls-remote --tags` lists up to `v6.2.5`). The PG 18 module here was built from libpg_query `18.1.0` with go-pgquery's Dockerfile plus the source edits in Q1.

The PG 18 input at `SqlOutputClauseTest.kt:137` is accepted by the PG 18 module. With the PG 17 module the same input failed with `ERROR syntax error at or near "WITH" (cursor 30)`. The PG 18 tree (scratch probe, `LibPgQuery.parseJsonText`) carries the prologue as `returningClause.options`:

```
UPDATE t SET x = 1 RETURNING WITH (OLD AS o, NEW AS n) o.x, n.x
"returningClause":{"options":[{"ReturningOption":{"option":"RETURNING_OPTION_OLD","value":"o","location":35}},{"ReturningOption":{"option":"RETURNING_OPTION_NEW","value":"n","location":45}}],"exprs":[{"ResTarget":{"val":{"ColumnRef":{"fields":[{"String":{"sval":"o"}},{"String":{"sval":"x"}}],"location":55}},"location":55}},...
```

`LibPgQueryRejectedInputsTest` executes the input on real servers: PG 18 runs it (`PG18 ACCEPT ... -> executed`), PG 16 and PG 17 reply `ERROR: syntax error at or near "WITH"`.

The tree shape differs between majors. In the PG 17 module the prototype read the items of `INSERT`, `UPDATE` and `DELETE` from `returningList` (its tests passed); in PG 18 they sit in `returningClause.exprs` (`INSERT INTO t(a) VALUES (1) RETURNING a, b AS c`, `DELETE ... RETURNING a` and `MERGE ... RETURNING t.a` all print `returningClause` in the PG 18 output). The first run of the unchanged prototype on the PG 18 module failed 30 tests, all through the old key (`returningList`); reading `returningClause.exprs` fixed 26 of them.

Consequence: a libpg_query module is tied to one PG major, both in which statements it accepts and in the shape of its trees. A PG 18 module parses PG 18 syntax (`RETURNING OLD/NEW`). PG 16 and 17 servers reject that syntax, so Norm rejecting it for those users loses nothing. That the PG 18 grammar accepts every input a PG 16 server accepts is **unverified**: the copied suite passes on the PG 18 module except for four inputs that the PG 18 server also rejects (C6), but the suite is not a sweep of PG 16 syntax. Grammar removals between majors are rare but exist. The practical shape is one PG 18 module, with the empty-list degrade for anything it rejects.

## Q3 — boundaries (C3)

Evidence: `LibPgQueryBoundariesTest` (assertions on real parse and scan output) and `LibPgQueryProbeTest` (prints raw output). All offsets are UTF-8 byte offsets, not `String` indexes; the unicode probe `SELECT 'é' AS a, ñ FROM t` shows `ñ` at `location` 18 where its `String` index is 17.

| Boundary | Verdict | Evidence for `SELECT id, name AS n, COUNT(*) FROM users WHERE a = 1` |
| --- | --- | --- |
| Start of each select item | derivable from the tree | `ResTarget.location` is 7, 11, 22. |
| End of a select item | text-dependent | `ResTarget` keys are only `name`, `val`, `location`. Inner item ends come from scan tokens (the comma before the next `location`). The last item has no following marker. |
| Start of `FROM` keyword | text-dependent | First `RangeVar.location` is 36, the table name. The scan token `FROM` starts at 31. `SelectStmt` has no `location`. |
| Start of `RETURNING` keyword | text-dependent | For `INSERT INTO t(a) VALUES (1) RETURNING a, b AS c`, first returned `ResTarget.location` is 38. The scan token `RETURNING` starts at 28. |
| CTE body extent | text-dependent | For `WITH x AS (SELECT 1 AS a), y AS (SELECT 2 AS b) SELECT a FROM x`, `CommonTableExpr.location` is 5 and 27 (the names), and `ctequery.SelectStmt` has no `location`. Scan tokens give the body parentheses `(10, 24)` and `(32, 46)` by depth counting. |
| Placeholder positions | text-dependent, see below | |

The prototype's answer for the last item's end: re-parse the prefix of tokens and take the shortest prefix whose output list equals the full parse's (appending the closing parentheses that were open before the item). It uses PG as the oracle and costs one parse per candidate token. This replaces `findTopLevelFromClauseKeyword` and `findTopLevelReturningKeyword`, but a cheaper rule is unverified.

Tokens from `pg_query_scan` also include comments (`/* c */`, `-- x`) with offsets, and carry a keyword kind (`k4` reserved, `k0` none in the probe output).

`?` placeholders: rejected by the parser. `SELECT a FROM t WHERE a = ? AND b = ?` returns `syntax error at or near "AND"` (cursor 29), and `SELECT ?, a FROM t` returns `syntax error at or near ","`. `$1` parses to `ParamRef{number:1, location:26}`. The scanner accepts `?` but does not split it from adjacent operator characters: `a=? AND b>=? AND d=-?` yields the tokens `=?`, `>=?`, `=-?`. P2 is confirmed. The prototype therefore rewrites each placeholder to the one-character literal `0` (and a pgjdbc `??` escape to ` ?`) before parsing, which keeps every offset. The item `expression` is cut from the original SQL with those offsets, so `SELECT coalesce(?, a) AS x, b FROM t` gives `coalesce(?, a)`, `SELECT j ?? 'k' AS has, id FROM t` gives `j ?? 'k'` and `INSERT ... RETURNING a, ? AS p` gives `?`, equal to the hand-written parser (`LibPgQueryBoundariesTest`). The placeholders are found by looking inside scan tokens made only of operator characters. `LibPgQueryBoundariesTest` shows the positions found this way equal `placeholderPositions` on 16 inputs covering string, `E''`, dollar-quoted, quoted-identifier and comment `?`, `??`, `???`, and operator-glued `?`. The rewrite itself is a text step, so placeholders remain text-dependent in the sense that Norm still defines "placeholder" by pgjdbc's rule.

## Q4 — files (C4)

| File | Verdict | Reason |
| --- | --- | --- |
| `SqlLexer.kt` | partly | Callers outside the file: `QueryFileParser.kt` (`skipLexicalToken`, in named-parameter conversion over `--` comment lines), `SqlPlaceholders.kt` (`skipLexicalToken`). `stripComments` and `collapseCosmeticWhitespace` serve `ComputedExpressionResolver.kt` and `NodeTreeProvenanceExpression.kt`, which work on text of expressions already extracted. `skipWhitespaceAndComments` and `skipOptionalKeyword` are also used by `SqlCteClause.kt`, which survives. |
| `SqlKeywordScanner.kt` | partly | `findTopLevelKeyword`, `findTopLevelReturningKeyword`, `findTopLevelFromClauseKeyword` and `splitAtTopLevel` have no callers outside the eight listed files. Their callers are `SqlOutputClause.kt` (replaced by the prototype) and `parseCteClause` in `SqlCteClause.kt` (`findTopLevelKeyword`), which survives. `findMatchingCloseParenthesis` is also called from `SqlCteClause.kt`. |
| `SqlOutputClause.kt` | partly | `parseSelectItems` is reproduced by the prototype (152 of 156 copied tests pass, see C6); `alignSelectItems` and `SelectItem` stay as the API. Surviving responsibilities: `parseColumnReference` (called at `NodeTreeProvenanceExpression.kt:84` on the text left after `splitTrailingImplicitAlias`, which the tree is not shown to replace), `parseOutputItemsWithAlias` (`NodeTreeProvenanceExpression.kt:50`, not prototyped; the three alias tests were removed from the copy), and `hasTopLevelSetOperation` (`ComputedExpressionResolver.kt:17`). The tree carries the answer for the last one (`SelectStmt.op` is `SETOP_UNION` for `SELECT a FROM t UNION SELECT b FROM u` and for the parenthesized form in `LibPgQueryProbeTest`), but no replacement was written or tested. |
| `SqlCteClause.kt` | remains | `parseCteClause` and `CteDefinition` are used at `NodeTreeProvenanceExpression.kt:174-196` (`scopedSqlCteDefinition` slices a CTE body by `bodyOpenParenthesis`/`bodyCloseParenthesis` and matches the folded `name` at `NodeTreeProvenanceExpression.kt:181`) and `SqlOutputClause.kt:101` (`mainQueryWindow`, replaced by the prototype). `NodeTreeProvenance.kt:25-26` and `PgNodeExpression.kt:365` mention it in KDoc only. The tree has CTE names (`ctename`) but no body extents (Q3), so the surviving responsibility is body extents for `NodeTreeProvenanceExpression.kt`; scan tokens could supply them (`LibPgQueryBoundariesTest`), which was not prototyped against that caller (**unverified**). |
| `SqlStarItem.kt` | partly | The tree classifies stars for `parseSelectItems`: `ColumnRef` ending in `A_Star`, or `A_Indirection` whose last indirection is `A_Star` (the prototype passes every star-guard case that PG accepts). Surviving: `isStarItem` (`ComputedExpressionResolver.kt:24`, on text), `splitTrailingImplicitAlias` (`NodeTreeProvenanceExpression.kt:83,132`, on text), and their private helper `stripCommentsAndWhitespace`. |
| `SqlParameterInferrer.kt` | does not exist | Commit `7c25f92` (HEAD of `main`) deleted it with `SqlParameterInferrerTest.kt`. |
| `SqlPlaceholders.kt` | partly | The lexical walk can move onto scan tokens (Q3). `replaceParameterPlaceholders` still has to rewrite text for `ColumnNullabilityAnalyzer.kt`, `ParameterNullabilityProbe.kt` and `ProsqlbodyProbe.kt`, and the pgjdbc `??` rule is Norm's own. `nonNullSentinel` is unrelated to lexing. |
| `QueryFileParser.kt` | remains | It splits a file on `-- name: X :cmd` comment lines and converts `:name` parameters. These are Norm's file format, not PostgreSQL grammar, so libpg_query cannot parse them. Its calls to `skipLexicalToken` and `placeholderPositions` could move to scan tokens. |

Removed completely: none. Partly: `SqlLexer.kt`, `SqlKeywordScanner.kt`, `SqlOutputClause.kt`, `SqlStarItem.kt`, `SqlPlaceholders.kt`. Remains: `SqlCteClause.kt`, `QueryFileParser.kt`. Does not exist: `SqlParameterInferrer.kt`. The prototype shows the select-list parsing responsibility (the keyword searches, the star classification and `parseSelectItems`) can move to the tree; it does not show the other callers can.

## C6 — prototype and suite results

Files (test source set only): `LibPgQuery.kt` (Chicory wrapper), `LibPgQuerySelectItems.kt` (`libPgQueryParseSelectItems`), `LibPgQuerySqlOutputClauseTest.kt` (the copy). Production `SqlOutputClause.kt`, its callers and `SqlOutputClauseTest.kt` are unchanged (`git status` shows no modification to them).

Edits from the original, all mechanical: class renamed to `LibPgQuerySqlOutputClauseTest`; 68 `parseSelectItems(` calls renamed `libPgQueryParseSelectItems(`; the two top-level data classes removed (declared in the original); the tests calling `parseOutputItemsWithAlias` removed (`a candidate AS word match is ASCII-only...`, `parseAliasToken's alias is RAW...`, and the nested `ExplicitAliasToken` class with its KDoc). The removed methods' `@Test` line and one adjoining blank line went with them, so the line ranges in the plan are a few lines wider than `:499-506` and `:1586-1593`.

Run on the PG 18 module: `./gradlew :generator:test --tests 'norm.generator.LibPgQuery*' --rerun` reports `195 tests completed, 4 failed` (BUILD FAILED in 50s). The JUnit XML gives 195 run, 191 passed, 4 failed, 0 skipped, of which 156 belong to the copy (152 passed, 4 failed). `./gradlew :generator:test --tests norm.generator.SqlOutputClauseTest --rerun` passes 163 of 163. `./gradlew spotlessApply` and `./gradlew :generator:check -x :generator:test` print `BUILD SUCCESSFUL`.

Adapting to PG 18 touched three prototype files beyond the module swap: `LibPgQuery.kt` (initial memory 34 to 32 pages), `LibPgQuerySelectItems.kt` and `LibPgQueryBoundariesTest.kt` (tree path `returningList` to `returningClause.exprs`, Q2). No assertion of the copied suite changed. The two PG 17 facts the old run recorded (`version` 170007 in the smoke output and the rejection of the `RETURNING WITH` input) were printed or computed, not asserted, so no assertion needed changing.

List (a), PostgreSQL rejects the input (executed on `postgres:18-alpine` by `LibPgQueryRejectedInputsTest`):

1. `UPDATE t SET x = 1 RETURNING id, a[b AS c` — `ERROR: syntax error at or near "AS"`.
2. `UPDATE t SET x = 1 RETURNING id, ) (bogus AS alias` — `ERROR: syntax error at or near ")"`.
3. `SELECT 2€, email FROM users` — `ERROR: trailing junk after numeric literal at or near "2€"`.
4. `SELECT "" FROM c` — `ERROR: zero-length delimited identifier at or near """"`.

The hand-written parser returns degraded items for these four malformed inputs and the tests assert that. The prototype returns an empty list, as C6 specifies.

The four failing tests are `extractAlias does not track square brackets...` (input 1), `extractAlias bails on an unmatched closing parenthesis...` (input 2), `a digit-leading fragment abutting a non-ASCII character...` (input 3) and `an empty quoted identifier yields no column name...` (input 4). The server errors above were printed by `LibPgQueryRejectedInputsTest` in the final run (`PG18 REJECT ... -> ERROR: ...`).

List (b), real failures: none. The earlier entry, `RETURNING WITH OLD-NEW alias prologue is stripped before the first item`, passes on the PG 18 module. The prototype passes every copied test whose input PostgreSQL 18 accepts.

Behaviors that the prototype matches by choice, which a naive tree reading does not:

- Implicit aliases (`count(*) total`, `preferences prefs`): the tree knows the alias (`ResTarget.name`) and would strip it, but 16 tests assert the hand-written parser's degrade (whole text as `expression`, `columnName` `null`). The first prototype run, which stripped implicit aliases, failed 21 tests: those 16, the 5 above, and nothing else. The prototype now treats an item with an implicit alias as no match to satisfy the suite. This is a loss of information relative to what the tree offers, not a libpg_query limitation.
- A set operation whose leftmost branch is parenthesized (`(SELECT a FROM x) UNION (SELECT b FROM y)`) must give no items, but the tree has no parenthesis marker (`larg` and `rarg` are plain `SelectStmt`s). The prototype walks tokens backwards from the first item to the `SELECT` keyword and checks for a preceding `(`. This also returns empty for `(SELECT a UNION SELECT b)`, where the hand-written parser returns `[SelectItem(expression="a UNION SELECT b", columnName=null, tableName=null)]` (not in the suite; asserted in `LibPgQueryBoundariesTest`).
- `TABLE t` parses to a synthesized `targetList` with `location: -1`; the prototype treats any negative location as no items.

Unverified:

- Behavior on Linux and Windows, and relocation requirements in the shadow jar.
- Whether the seven WASI source edits for 18.1.0 are the minimum, and whether a later libpg_query release keeps them applicable (each edit was made after a compile error; none was tried in isolation).
- Chicory AOT compiler speed.
- Callers of `parseOutputItemsWithAlias` and `parseCteClause` (`NodeTreeProvenanceExpression.kt`) against the tree.
- A cheaper end-of-last-item rule than prefix re-parsing.
