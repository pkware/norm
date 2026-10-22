# wasi-sdk-34, linux/amd64 manifest.
FROM ghcr.io/webassembly/wasi-sdk@sha256:9515a0625f6ac6002aa2c285fd718cba0575b507bfaa3687ba41fea822a2bfa5 AS build

ARG LIBPG_QUERY_COMMIT=c1546e7e97edc93fe8474d7a59881e91fbf48cfc
ARG BINARYEN_VERSION=version_125
ARG BINARYEN_X86_64_SHA256=7c3bc16599c8274a04d34a504fe4be2047884f900e0e2da2f6fb9cd667183be4

RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl git jq patch \
  && rm -rf /var/lib/apt/lists/*

COPY install-binaryen.sh /tmp/install-binaryen.sh
RUN ["/tmp/install-binaryen.sh"]

ENV PATH="/opt/binaryen/bin:${PATH}"

WORKDIR /checkout
RUN git init -q . \
  && git fetch -q --depth 1 https://github.com/pganalyze/libpg_query.git "${LIBPG_QUERY_COMMIT}" \
  && git checkout -q FETCH_HEAD \
  && test "$(git rev-parse HEAD)" = "${LIBPG_QUERY_COMMIT}"

RUN jq -r '[.. | objects | select(.c_type? == "ParseLoc") | .name] | unique | .[]' /checkout/srcdata/struct_defs.json \
    > /tmp/libpg_query_location_fields.unsorted \
  && test -s /tmp/libpg_query_location_fields.unsorted \
  && LC_ALL=C sort -u /tmp/libpg_query_location_fields.unsorted > /libpg_query_location_fields.txt

RUN jq -e --rawfile names /libpg_query_location_fields.txt \
    '($names | split("\n") | map(select(. != ""))) as $locations | [.. | objects | select(.c_type? != null and (.name? as $name | $locations | index($name)) != null and .c_type != "ParseLoc" and .c_type != "char*")] | length == 0' \
    /checkout/srcdata/struct_defs.json

COPY flatten.sh /tmp/flatten.sh
COPY patches /tmp/patches
WORKDIR /workspace
RUN /tmp/flatten.sh /checkout /workspace \
  && for patch_file in /tmp/patches/*.patch; do patch -p1 -F 0 < "${patch_file}"; done

ENV CFLAGS="--target=wasm32-wasip1 --sysroot=/opt/wasi-sdk/share/wasi-sysroot -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false -O3 -fno-strict-aliasing -fwrapv -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_MMAN -D_WASI_EMULATED_SIGNAL"

RUN printf '%s\n' *.c | xargs -P "$(nproc)" -n 4 \
  $CC -Iinclude -Iinclude/postgres -Iinclude/upb -Iinclude/upb/third_party/utf8_range \
  -fstack-protector -std=gnu99 -Wno-unknown-warning-option $CFLAGS -c
RUN $AR rs libpg_query.a *.o

ENV LDFLAGS="--target=wasm32-wasip1 --sysroot=/opt/wasi-sdk/share/wasi-sysroot -mexec-model=reactor -lsetjmp -lwasi-emulated-process-clocks -lwasi-emulated-mman -lwasi-emulated-signal -Wl,-z,stack-size=1048576"

RUN $CC -o libpg_query-noopt.wasm $LDFLAGS \
  libpg_query.a \
  -Wl,--export=malloc \
  -Wl,--export=free \
  -Wl,--export=pg_query_init \
  -Wl,--export=pg_query_parse \
  -Wl,--export=pg_query_free_parse_result \
  -Wl,--export=pg_query_parse_protobuf \
  -Wl,--export=pg_query_free_protobuf_parse_result \
  -Wl,--export=pg_query_parse_plpgsql \
  -Wl,--export=pg_query_free_plpgsql_parse_result \
  -Wl,--export=pg_query_scan \
  -Wl,--export=pg_query_free_scan_result \
  -Wl,--export=pg_query_split_with_scanner \
  -Wl,--export=pg_query_split_with_parser \
  -Wl,--export=pg_query_normalize \
  -Wl,--export=pg_query_free_normalize_result \
  -Wl,--export=pg_query_fingerprint \
  -Wl,--export=pg_query_free_fingerprint_result \
  -Wl,--export=pg_query_deparse_protobuf \
  -Wl,--export=pg_query_free_deparse_result \
  -Wl,--export=XXH3_64bits_withSeed

RUN wasm-opt -o libpg_query.wasm --translate-to-exnref -O3 libpg_query-noopt.wasm

FROM scratch AS artifact
COPY --from=build /workspace/libpg_query.wasm /libpg_query.wasm
COPY --from=build /libpg_query_location_fields.txt /libpg_query_location_fields.txt
