#!/bin/sh
# Usage: flatten.sh <libpg_query checkout> <output directory>
#
# Collapses libpg_query's source tree into the single-directory layout that the Dockerfile compiles:
# every C file in the output root, headers under include/.
set -eu

checkout=$1
output=$2

mkdir -p "$output/include/protobuf" "$output/include/xxhash" "$output/include/upb"

find "$checkout/src" -maxdepth 1 -type f \( -name '*.c' -o -name '*.h' \) -exec cp {} "$output" \;
find "$checkout/src/postgres" -maxdepth 1 -type f -name '*.c' -exec cp {} "$output" \;
cp -R "$checkout/src/postgres/include" "$output/include/postgres"
cp -R "$checkout/src/include/." "$output/include/"
cp "$checkout/pg_query.h" "$checkout/postgres_deparse.h" "$checkout/pg_query_scan_tokens.h" "$output/include/"

cp "$checkout"/protobuf/*.h "$output/include/protobuf/"
cp "$checkout/protobuf/pg_query.upb_minitable.c" "$output"

cp "$checkout/vendor/xxhash/xxhash.h" "$output/include/"
cp "$checkout/vendor/xxhash/xxhash.h" "$output/include/xxhash/"
cp "$checkout/vendor/xxhash/xxhash.c" "$output"

cp -R "$checkout/vendor/upb/upb" "$checkout/vendor/upb/third_party" "$output/include/upb/"
cp "$checkout/vendor/upb/upb.c" "$output"
mv "$output/include/upb/third_party/utf8_range/utf8_range.c" "$output"
