# Changelog

## [0.2.1](https://github.com/pkware/norm/compare/v0.2.0...v0.2.1) (2026-05-15)


### Bug Fixes

* **ci:** use block annotation for gradle.properties version; inline comments not valid in .properties ([041a575](https://github.com/pkware/norm/commit/041a575bf708677a6fc5ceb6dc03d3273b8ece41))

## [0.2.0](https://github.com/pkware/norm/compare/v0.1.4...v0.2.0) (2026-05-15)


### Features

* add explicit transaction management for standalone (non-framework) use ([fc233e1](https://github.com/pkware/norm/commit/fc233e1b74269bfcc6da5db8ece1ccf8191549d6))
* determine result column nullability from PostgreSQL query parse trees ([#66](https://github.com/pkware/norm/issues/66)) ([6638cd7](https://github.com/pkware/norm/commit/6638cd7b69ede178948068e296dcdb2bbcfa6737))
* generate batch INSERT with RETURNING for CRUD-synthesized queries ([d7317c3](https://github.com/pkware/norm/commit/d7317c3a6dc5830c4d3096f95154aa11af6d7828))
* Improve code generation ([600cf86](https://github.com/pkware/norm/commit/600cf86b9031c6c53b608b67dd14f916e9bb7928))
* speed up generation container with tmpfs and no-durability flags ([1652374](https://github.com/pkware/norm/commit/1652374a7568faa6db8e4a1fa6ebdaa0743d0d18))
* stop pushing postgresql driver onto consumers; document JDBC driver requirement ([01bec9a](https://github.com/pkware/norm/commit/01bec9ae49ccc03d38f980fa613b968644e6c486)), closes [#49](https://github.com/pkware/norm/issues/49)


### Bug Fixes

* apply column() type mappings to aliased columns in RETURNING clauses ([0b29124](https://github.com/pkware/norm/commit/0b291249a613163c09cd66d43cc506a3cca24aa2))
* **ci:** move extra-files into package config so release-please updates gradle.properties ([335c87b](https://github.com/pkware/norm/commit/335c87b15e1eab096abb41f40448d925e3df714c))
* **ci:** SNAPSHOT bump runs in release-please job, not separate workflow ([fe8e183](https://github.com/pkware/norm/commit/fe8e183db397d184c740e9035d7395ede1a64fc0)), closes [#78](https://github.com/pkware/norm/issues/78)
* **ci:** use x-release-please-version annotation; remove unused search/replace ([a321779](https://github.com/pkware/norm/commit/a3217795c32e1148b789af7b0e5c6d0f59c68a18))
* correct GROUPING SETS/CUBE/ROLLUP nullability on PostgreSQL 16 and 17 ([bf9f976](https://github.com/pkware/norm/commit/bf9f976bf2d406b105810c808c0776b6979600ea))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.5.0 ([f539456](https://github.com/pkware/norm/commit/f53945676a86c1ac5bfd784284131d45d7730d99))
* **deps:** update dependency dev.detekt:dev.detekt.gradle.plugin to v2.0.0-alpha.3 ([#47](https://github.com/pkware/norm/issues/47)) ([47c9a35](https://github.com/pkware/norm/commit/47c9a359bd2bd9f44cd4c69a52ae93bc69ac98ff))
* **deps:** update dependency org.postgresql:postgresql to v42.7.11 ([828c793](https://github.com/pkware/norm/commit/828c793d6aefeaa276b37590c563de8823f239d5))
* **deps:** update micronaut to v4.10.22 ([8eb7126](https://github.com/pkware/norm/commit/8eb7126f698f6ef19901744b7b6eb204007fb8f4))
* **deps:** update micronaut to v4.10.23 ([2902805](https://github.com/pkware/norm/commit/290280556fa24171ff9de0db0d0276baa639f64a))
* **deps:** update micronautdata to v4.14.4 ([e631255](https://github.com/pkware/norm/commit/e631255085a02921cc4fb2d57bf28cc66281477c))
* **deps:** update wire to v6.3.0 ([2711d3b](https://github.com/pkware/norm/commit/2711d3beffc08b24a6618156ece371b6cf196cb8))
* **deps:** update wire to v6.4.0 ([39b3f19](https://github.com/pkware/norm/commit/39b3f195224f7c0351e928e49fb866ca3acde563))
* parseTargetEntry extracted fields from nested TARGETENTRY scope ([bfa4fb7](https://github.com/pkware/norm/commit/bfa4fb7bdc204fd6a24ee4736a63bf2f7eba7950))
* require Postgres ready log before accepting connections in e2e tests ([169d235](https://github.com/pkware/norm/commit/169d23563eddf12b31b2ce0b07ebbb3fbdfd7cc0))
* resolve 17 nullability analysis gaps found by end-to-end tests ([1787410](https://github.com/pkware/norm/commit/178741052dc6b130c7bc23797149cc151b2df357))
* UPDATE SET nullable columns now generate nullable parameter types ([cd0d57a](https://github.com/pkware/norm/commit/cd0d57a294cb16d10fb0344ab87d8bcbee2793c5))
* UPDATE without WHERE now correctly inherits column nullability ([4226c4b](https://github.com/pkware/norm/commit/4226c4b7fd4f4457594b6c2e0eceaa3b0d79c1f1))
* use PostgreSQL query trees for accurate outer join nullability ([763dfe8](https://github.com/pkware/norm/commit/763dfe802fe5e7eb9386b241044c4ccac95a91c4))
