# libpg_query WASI build

Builds `generator/src/main/resources/libpg_query.wasm` and `libpg_query_location_fields.txt` from libpg_query 18.1.0
(commit `c1546e7e97edc93fe8474d7a59881e91fbf48cfc`) with wasi-sdk 34 and binaryen 125.

```sh
docker build --platform=linux/amd64 --output type=local,dest=build/libpg_query-wasm buildtools/libpg_query-wasm
cmp build/libpg_query-wasm/libpg_query.wasm generator/src/main/resources/libpg_query.wasm
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
