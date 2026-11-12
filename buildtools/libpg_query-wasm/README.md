# libpg_query WASI build

Builds `generator/src/main/wasm/libpg_query.wasm` and `libpg_query_location_fields.txt` from the libpg_query
release named by `LIBPG_QUERY_VERSION` in the `Dockerfile`, with wasi-sdk 34 and binaryen 125. The build fails when the
tag does not point at `LIBPG_QUERY_COMMIT`.

```sh
docker build --platform=linux/amd64 --output type=local,dest=build/libpg_query-wasm buildtools/libpg_query-wasm
cmp build/libpg_query-wasm/libpg_query.wasm generator/src/main/wasm/libpg_query.wasm
cmp build/libpg_query-wasm/libpg_query_location_fields.txt generator/src/main/resources/libpg_query_location_fields.txt
```

- `Dockerfile` clones the pinned commit, flattens it with `flatten.sh`, applies `patches/` and compiles.
- `patches/01-go-pgquery-wasi.patch` swaps `sigsetjmp` for `setjmp` and drops `EHOSTDOWN`. It follows
  go-pgquery's `buildtools/wasm/patch.txt` at commit `1d929f3206792739f6874ab540c51cc72053bc13`.
- `patches/02-libpg-query-18-wasi.patch` stubs the backend pieces that need `netdb.h`, `syslog.h` and `getpid`.
- `libpg_query_location_fields.txt` lists, one per line and sorted, the fields whose `c_type` is `ParseLoc` in
  `srcdata/struct_defs.json` of the pinned checkout. `LibPgQuery` converts these from byte offsets to `String`
  indexes by name, on whichever node the field appears. The Dockerfile extracts the list with `jq`, then runs a
  `jq -e` check that fails the build when any struct has a field with a listed name and a `c_type` other than
  `ParseLoc` or `char*`. `CreateTableSpaceStmt.location` is the one `char*` field with a listed name. It holds a
  string, which `ParseTreeOffsets` leaves unchanged.
- The module targets `wasm32-wasip1`: no threads, and it defines and exports its own `memory`.

Both files are committed. Changing the pin, the patches or the export list requires rebuilding them
and committing the results in the same change.

## Updating libpg_query

Renovate opens a pull request that changes `LIBPG_QUERY_VERSION` and `LIBPG_QUERY_COMMIT`. The `Rebuild and compare`
job of `.github/workflows/libpg-query-wasm.yml` fails on it until the rebuilt files are committed to the same branch.

1. Rebuild with the `docker build` command above. When a file in `patches/` no longer applies, rewrite it against the
   flattened tree of the new release. A release can also change which files need patching.
2. Copy `build/libpg_query-wasm/libpg_query.wasm` into `generator/src/main/wasm/` and
   `build/libpg_query-wasm/libpg_query_location_fields.txt` into `generator/src/main/resources/`.
3. Rebuild once more and run the two `cmp` lines. The CI job prints the sha256 of the built and the committed module
   and fails when they differ.
4. Run the grammar sweep with `./gradlew :generator:test --tests 'norm.generator.libpgquery.RegressSweepTest'`. A
   statement that stops parsing is a grammar change to review. Add it to `reject-list.txt` only when PostgreSQL
   rejects it too, as `generator/src/test/resources/pg-regress/README.md` describes.
5. Run `./gradlew :generator:generateScannerBaseline` and review the diff of `scanner-baseline/` and
   `sweep-skips.txt` under `generator/src/test/resources/`.
6. Run `./gradlew check` and commit everything to the Renovate branch.
