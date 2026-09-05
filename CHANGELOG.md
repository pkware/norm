# Changelog

## [0.5.0](https://github.com/pkware/norm/compare/v0.4.2...v0.5.0) (2026-09-05)


### Features

* add EXPLAIN (FORMAT JSON)-based MERGE side-nullability detection (Stage 2) ([a34d2e3](https://github.com/pkware/norm/commit/a34d2e3a4cf63b207780e25112a84f8c002c5110))
* add prosqlbody-based nullability probe alongside CREATE VIEW, prove parity (Stage 1) ([bdd11e4](https://github.com/pkware/norm/commit/bdd11e402a4bcb3169f0e504cc2504b24ae76e43))
* resolve [@property](https://github.com/property) provenance from the PostgreSQL node tree ([462b039](https://github.com/pkware/norm/commit/462b03958f54aa0cd1af211189e3321d19b8035d))
* route DML/CTE/MERGE nullability through prosqlbody in production ([59a9b45](https://github.com/pkware/norm/commit/59a9b45b3b60b1c8fd3931fea2adc5ccdcb46104))
* route plain SELECT through prosqlbody and sweep dead DML-conversion code ([5de7a61](https://github.com/pkware/norm/commit/5de7a61b045d8eb3cf217d72cff51a23de1a23bd))


### Bug Fixes

* analyze nullability for MERGE fed by a CTE source ([f32a8d6](https://github.com/pkware/norm/commit/f32a8d6fe5848d6ab78aaa3b2ca9f9383050fe18))
* analyze view expressions instead of trusting source column names ([fcb25bf](https://github.com/pkware/norm/commit/fcb25bf621160730d01b26aec9a5a8087d5e89a7)), closes [#256](https://github.com/pkware/norm/issues/256)
* apply any-nullable-source-wins reduction to view nullability by name ([bef131e](https://github.com/pkware/norm/commit/bef131e39698f1cc86644666e42e07ccd4079e91))
* ask KotlinPoet whether a [@property](https://github.com/property) name needs backticks ([bd42334](https://github.com/pkware/norm/commit/bd42334014b0ed2ae3b0bc0e72fa2a3b8cd3f3fc))
* attribute a MERGE's join by locating its own ModifyTable node, not the first join found ([18c09f0](https://github.com/pkware/norm/commit/18c09f00307c055fdae801f7148133ce5e7f88d6))
* bail conservatively on a Unicode-escape identifier in referencesAnyName ([51fce48](https://github.com/pkware/norm/commit/51fce4851de840cdb7d12b81c4386b7bf03dbd4b))
* bail instead of clamping when a lexical scan's bracket depth goes negative ([845720f](https://github.com/pkware/norm/commit/845720f553cdd67845cdfb206c5c495591d08b7c))
* bound-check truncated \u escapes in JsonValue parser ([1aad12f](https://github.com/pkware/norm/commit/1aad12f88fba4041af3805f5dac4509facf0f68a))
* correct SUBLINK_TYPE_ALL's enum value and prove ALL_SUBLINK non-null ([3c62130](https://github.com/pkware/norm/commit/3c62130d6e9fa59d6345d598ae84a02c6cbc8291))
* correctly decode quoted identifiers in referencesAnyName's sibling-name scan ([c3b92df](https://github.com/pkware/norm/commit/c3b92dffdf4bcd3bfe78cbde67e4f74eb113ec91))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.10.1 ([69e44fb](https://github.com/pkware/norm/commit/69e44fb044ee5b08f6ca25c812ba2320a2867d36))
* **deps:** update dependency io.micronaut.data:micronaut-data-processor to v5.1.3 ([8fdc2a7](https://github.com/pkware/norm/commit/8fdc2a7fe64c46a77a7e9aac2595f27c47b66bb9))
* **deps:** update dependency io.micronaut.sql:micronaut-jdbc-hikari to v7.1.2 ([187e75d](https://github.com/pkware/norm/commit/187e75ddda6e9348989855ea66b20c493e97cd5d))
* **deps:** update dependency org.commonmark:commonmark to v0.30.0 ([1bdb28b](https://github.com/pkware/norm/commit/1bdb28bad400139766ed93c78cc6277d7ee78ee5))
* **deps:** update micronaut to v5.1.13 ([d43c6e9](https://github.com/pkware/norm/commit/d43c6e98c080a70013eae24d60b4fc78ba2e8117))
* derive JSON constructor nullability from its arguments ([c83f2f5](https://github.com/pkware/norm/commit/c83f2f5cd94f929420ed058b94059c006260b2ed))
* drop the per-assignment parameter-trust gate for the whole-statement one ([513af56](https://github.com/pkware/norm/commit/513af567e520f0c649c1b4808979870108236f0c))
* fail safe on an unanalyzable branch or a non-converged pass limit in the recursive-CTE fixpoint ([c00e20c](https://github.com/pkware/norm/commit/c00e20c3150bb4d98e45b3e9b08fc906beb71fcd))
* flip PgNodeTreeParser's CONST/FUNCEXPR field defaults to the safe direction ([7158f96](https://github.com/pkware/norm/commit/7158f96be30aeb170a9eec7aea72d7de7ee4195b))
* generate compilable CRUD SQL for quoted column names ([57b01b7](https://github.com/pkware/norm/commit/57b01b70a4223869b45bb90ac1287b96baedd4ff))
* guard parseSelectItems against a shifted item-to-column mapping ([8e435bd](https://github.com/pkware/norm/commit/8e435bdf90f13141fc9158bc9b4a4c930f6c438d))
* handle comments and whitespace after the alias in a RETURNING WITH (OLD AS ...) prologue ([b250c33](https://github.com/pkware/norm/commit/b250c3304ea3a7b399bebf7c08f8e42bedc89ddb))
* iterate recursive CTE nullability to a fixpoint instead of one pass ([69f66f7](https://github.com/pkware/norm/commit/69f66f7e3703e0e9f5ad3fa7ada1a2fbba3b7873))
* make extractFieldExpression depth-one-aware, closing a wrong-subquery hazard in nested CASE/JSONEXPR parsing ([6ce3856](https://github.com/pkware/norm/commit/6ce3856400e6544724d294d08566aac7a400f05d))
* make prosqlbody's parameter-trust gate per-assignment, not per-statement ([1e91900](https://github.com/pkware/norm/commit/1e91900dd0d24f3957de270f1cb336bc7180960a))
* order Flyway migrations globally across schemas entries, not per-directory ([e30ad16](https://github.com/pkware/norm/commit/e30ad163905d2fcf5b0c5a364a7e5a82d67c0642))
* prove ANY_SUBLINK non-null only under three-valued-logic-safe conditions ([e679d19](https://github.com/pkware/norm/commit/e679d199da0667ff15435315eab76ef35846f531)), closes [#239](https://github.com/pkware/norm/issues/239)
* read a domain's UUID base type through the class-hinted getObject overload ([138aeb6](https://github.com/pkware/norm/commit/138aeb6aaefb083fb87983f8f1bbaf0a47e0e355))
* recover column comments lost to folding and IS DISTINCT FROM ([935c22c](https://github.com/pkware/norm/commit/935c22c2622a1053d5b144461623d0cd63d343a5))
* recover GROUPING SETS columns PostgreSQL never nulls ([6fa1f7c](https://github.com/pkware/norm/commit/6fa1f7c2d2ade81b674472656fb7b5fdc62f9729)), closes [#240](https://github.com/pkware/norm/issues/240)
* recurse into JsonExpr's children in containsVarOutsideRelation ([444f26b](https://github.com/pkware/norm/commit/444f26bcf09fc5b76e8e56552acf9e1fd3966cfb))
* render KDoc that survives Markdown and KotlinPoet escaping ([3e1e96c](https://github.com/pkware/norm/commit/3e1e96c9d728fff960267136cc192966265dac0c))
* replace evaluateJsonExpr's JsonBehaviorType deny-list with a verified allow-list ([8f69da7](https://github.com/pkware/norm/commit/8f69da7b0cbb2119d35564812a9799a33cf94e5a))
* report nullable, not NOT NULL, when the unconvertible-DML probe can't prove a column ([de988db](https://github.com/pkware/norm/commit/de988db1d959bc8cd764a9b8c73d3960007fef56))
* resolve JDBC types for every domain base type resolveBaseType accepts ([e4fc6d3](https://github.com/pkware/norm/commit/e4fc6d3b4fea28fe98c949e948c32e0d561acde0))
* resolve PostgreSQL 18 GROUP RTE vars to their group expressions ([8dbc7da](https://github.com/pkware/norm/commit/8dbc7da48519ef8a7092008a1b188b3fbc593281))
* resolve sublink subselects against derived tables and enclosing CTEs ([9d5fe1b](https://github.com/pkware/norm/commit/9d5fe1bfded2ccf1307418a2cdfafbd17c38e344)), closes [#257](https://github.com/pkware/norm/issues/257)
* restore original comments in the four frozen embed scenarios ([4a0cdff](https://github.com/pkware/norm/commit/4a0cdffd268b0c2e9ac139ac0d72b0c293d60849))
* separate the nullability probe's function body from its terminator with a newline ([8d4fd24](https://github.com/pkware/norm/commit/8d4fd2460aeae88b7d8176156924cf4a5bc3124d))
* source resolveCteOutputExpression's reserved-word set from the live server, not a hardcoded snapshot ([c0983fc](https://github.com/pkware/norm/commit/c0983fc54488ed42f683d77ad8d9e6c2c993f730))
* spell out abbreviated JDBC/expression identifiers introduced by this branch ([c60a441](https://github.com/pkware/norm/commit/c60a4418e42eab236e0cb89f263927b50f4c0202))
* stop aliasing a mutable task list for IDE-sync dependency wiring ([2b6b61d](https://github.com/pkware/norm/commit/2b6b61df76a6ab15eb48220fcb31eaf9649a91f5))
* stop inferring JSON_VALUE/JSON_QUERY non-null from ON EMPTY/ON ERROR codes alone ([c86b06d](https://github.com/pkware/norm/commit/c86b06d29eae9401e7e4f3e56530b3e08f671bf0))
* strip the PG18 RETURNING WITH (OLD/NEW alias) prologue before parsing output items ([110bfdc](https://github.com/pkware/norm/commit/110bfdc7bda0bbc8fea42b73e171861a4ab33493))
* truncate identifiers to PostgreSQL's 63-byte limit ([cc0e1f7](https://github.com/pkware/norm/commit/cc0e1f7f4bc1a223d1614584f3f5860613021a17)), closes [#245](https://github.com/pkware/norm/issues/245)

## [0.4.2](https://github.com/pkware/norm/compare/v0.4.1...v0.4.2) (2026-08-22)


### Bug Fixes

* analyze chained data-modifying CTEs ([#202](https://github.com/pkware/norm/issues/202)) ([f98bdd9](https://github.com/pkware/norm/commit/f98bdd9b75d2f2a532e39ae8d9d0118839b965aa))
* analyze data-modifying CTE bodies via join-preserving SELECT conversion, not isolated metadata probes ([#203](https://github.com/pkware/norm/issues/203)) ([d588645](https://github.com/pkware/norm/commit/d58864557b47f8e54c09edc8376703cdaa3e0031))
* bind and read plain array columns correctly ([#190](https://github.com/pkware/norm/issues/190), [#192](https://github.com/pkware/norm/issues/192)) ([af98e41](https://github.com/pkware/norm/commit/af98e4104fc416c7b5f5ce6a123edfa41949a478))
* bind plain jsonb parameters with setObject(Types.OTHER) ([0de21d2](https://github.com/pkware/norm/commit/0de21d25c4f301c28715a2ca9f0d7e395a6b832f)), closes [#187](https://github.com/pkware/norm/issues/187)
* classify a CTE body by the statement its own nested WITH introduces ([#205](https://github.com/pkware/norm/issues/205)) ([9650729](https://github.com/pkware/norm/commit/9650729a0196ccf52ea4a6389b7b8f10dd1a37b2))
* close the sibling-CTE and grouping-set nullability gaps ([#208](https://github.com/pkware/norm/issues/208)) ([9c5398d](https://github.com/pkware/norm/commit/9c5398d9dd86af108e611503b4a2918031379e8c))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.10.0 ([75938c8](https://github.com/pkware/norm/commit/75938c890b023f01ff2f4ebcc76cdba99575816f))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.9.0 ([81b32c2](https://github.com/pkware/norm/commit/81b32c22ebae5f7a57d9090503bd0722b50d637f))
* **deps:** update dependency dev.detekt:dev.detekt.gradle.plugin to v2.0.0-alpha.6 ([350a1b4](https://github.com/pkware/norm/commit/350a1b4355902c82de4d0fd033ec9aa5c9001734))
* **deps:** update dependency io.micronaut.test:micronaut-test-junit5 to v5.1.0 ([3930c77](https://github.com/pkware/norm/commit/3930c77d33b70847a34ce352349b846fcbb70acd))
* **deps:** update dependency io.micronaut.test:micronaut-test-junit5 to v5.1.1 ([de70baf](https://github.com/pkware/norm/commit/de70baf5ed3f4b8250bd6419f44be5d290565502))
* **deps:** update dependency org.junit.jupiter:junit-jupiter-params to v6.1.2 ([917f597](https://github.com/pkware/norm/commit/917f5978c7994c4e26f559f88f01208c34ef06d3))
* **deps:** update dependency org.junit.jupiter:junit-jupiter-params to v6.1.3 ([437e632](https://github.com/pkware/norm/commit/437e63247d8a4d53b92e34416830a2decc0b0893))
* **deps:** update dependency org.springframework.boot:spring-boot-dependencies to v4.1.1 ([3656cf4](https://github.com/pkware/norm/commit/3656cf46f80854e4f075daa81fe7efe6356a37e3))
* **deps:** update kotlin monorepo to v2.4.10 ([f1675bb](https://github.com/pkware/norm/commit/f1675bb0024e6bccb290d9be7b0539b73799fc9e))
* **deps:** update micronaut to v5.1.10 ([170349e](https://github.com/pkware/norm/commit/170349e190457e3218931e349425246789b882f7))
* **deps:** update micronaut to v5.1.11 ([db2275b](https://github.com/pkware/norm/commit/db2275b634340afea2ed035ed16250f2a6bc0ee4))
* **deps:** update micronaut to v5.1.12 ([3bb4816](https://github.com/pkware/norm/commit/3bb4816fb73a21dea562816715824353a29be251))
* **deps:** update micronaut to v5.1.8 ([a82d811](https://github.com/pkware/norm/commit/a82d8112a64f286f8de8dacd765f2bf873744a85))
* **deps:** update micronaut to v5.1.9 ([1ee3cc4](https://github.com/pkware/norm/commit/1ee3cc4ec7b551e11398e8732c974beea1f1e651))
* **deps:** update micronautdata to v5.1.0 ([c3cf86c](https://github.com/pkware/norm/commit/c3cf86cb4730b3c91064710df19a63804acee304))
* **deps:** update micronautdata to v5.1.1 ([ef93391](https://github.com/pkware/norm/commit/ef933911bdcfde697f04caedcc4e2b1a70402b0f))
* **deps:** update micronautdata to v5.1.2 ([ca9e7ac](https://github.com/pkware/norm/commit/ca9e7acfd9749c24b580812838b6dffaaecc550b))
* **e2e-tests-micronaut:** declare kspTest so @MicronautTest bean definitions generate ([b13b6ec](https://github.com/pkware/norm/commit/b13b6ecd27a4b929adff4bf283e835628297ff0b))
* give the SQL scanners lexical awareness of strings, comments, and brackets ([cb1300e](https://github.com/pkware/norm/commit/cb1300e0b2a01174177e9591197876cab39f0dae))
* give top-level DML RETURNING the same nullability safety net as CTE bodies ([909a11f](https://github.com/pkware/norm/commit/909a11f53286dca3c7adf849c966df45db457568)), closes [#207](https://github.com/pkware/norm/issues/207)
* locate the main query's output clause, not the first SELECT anywhere ([#212](https://github.com/pkware/norm/issues/212)) ([406f4d9](https://github.com/pkware/norm/commit/406f4d95f5c4b4af35eb467e62e6c95cda91891f))
* map oid[] to Array&lt;Long?&gt; rather than Array&lt;Blob?&gt; ([#196](https://github.com/pkware/norm/issues/196)) ([1a8bfc5](https://github.com/pkware/norm/commit/1a8bfc5678251ef101a64d341bdb3bf9f4e73976))
* map Postgres json alongside jsonb ([#191](https://github.com/pkware/norm/issues/191)) ([941d89a](https://github.com/pkware/norm/commit/941d89aa1c6f55383bf55fffc727c16a4dc9f215))
* match PostgreSQL's identifier character classes per lexical position ([#219](https://github.com/pkware/norm/issues/219)) ([e0ac252](https://github.com/pkware/norm/commit/e0ac252ac327e92079cae63d57e8e4750d4b099d))
* narrow result nullability using WHERE-clause predicates ([58f9029](https://github.com/pkware/norm/commit/58f90294d46ca5e52feb4f0e23944f0be944f66c)), closes [#186](https://github.com/pkware/norm/issues/186)
* order schema replay the way Flyway orders migrations ([22f6657](https://github.com/pkware/norm/commit/22f66579fbc9422e1e4b002f66ebc3f5172d365a))
* quote the nullability stub's column aliases ([#204](https://github.com/pkware/norm/issues/204)) ([01286ad](https://github.com/pkware/norm/commit/01286ad102f3b75f3a90b2394c1415e2da39f3ec))
* recognize a star by its trailing alias, not by enumerating what may precede it ([#215](https://github.com/pkware/norm/issues/215)) ([e500f3c](https://github.com/pkware/norm/commit/e500f3c6554446448539ad6ff426b8883b693852))
* resolve [@property](https://github.com/property) provenance through CTE bodies, correct-or-silent ([b41b813](https://github.com/pkware/norm/commit/b41b8137f158a8feaaa1706f19c17b2bd2dce78e)), closes [#229](https://github.com/pkware/norm/issues/229)
* resolve RETURNING nullability against SET-assigned values ([#228](https://github.com/pkware/norm/issues/228)) ([431126b](https://github.com/pkware/norm/commit/431126b6f179d6ef357c2ff2bbdd1d6cb4c7301a))
* resolve unknown-nullability RETURNING columns by probing the target relation ([#226](https://github.com/pkware/norm/issues/226)) ([0769c9a](https://github.com/pkware/norm/commit/0769c9ac4a6b0a7e6c42570124949005e16c094f))
* return a correctly sized array from batch overloads ([#189](https://github.com/pkware/norm/issues/189)) ([db3d420](https://github.com/pkware/norm/commit/db3d420f72814e41bc58e4f8f9dc135bba552b86))

## [0.4.1](https://github.com/pkware/norm/compare/v0.4.0...v0.4.1) (2026-07-09)


### Bug Fixes

* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.8.0 ([6494b3d](https://github.com/pkware/norm/commit/6494b3df20aadbf762e7c4f794fa2fea317eaf14))
* **deps:** update dependency io.micronaut.sql:micronaut-jdbc-hikari to v7.0.2 ([f0b2e87](https://github.com/pkware/norm/commit/f0b2e877e466193f8c118e134be2fea27b05f7bb))
* **deps:** update dependency io.micronaut.sql:micronaut-jdbc-hikari to v7.1.0 ([b35c1f9](https://github.com/pkware/norm/commit/b35c1f94d5f2870cddd782933d456206df249069))
* **deps:** update dependency org.junit.jupiter:junit-jupiter-params to v6.1.1 ([c43f3ad](https://github.com/pkware/norm/commit/c43f3adf01885ba3191b20e1b0fa72ed83c5ce1f))
* **deps:** update dependency org.postgresql:postgresql to v42.7.12 ([cb4bcd4](https://github.com/pkware/norm/commit/cb4bcd4fc356a8d458ab0af49b7798c63702d42d))
* **deps:** update dependency org.postgresql:postgresql to v42.7.13 ([c887ea3](https://github.com/pkware/norm/commit/c887ea3d10f93da4f6e2bbda71d03ac4d6042d87))
* **deps:** update micronaut to v5.1.3 ([9e7aae9](https://github.com/pkware/norm/commit/9e7aae90ed3aa3ad2e52b52f74fee94a3e5476aa))
* **deps:** update micronaut to v5.1.5 ([9f8b69a](https://github.com/pkware/norm/commit/9f8b69a22a6a8b625118efe27d52bc13c1d9400b))
* **deps:** update micronautdata to v5.0.5 ([d6297ff](https://github.com/pkware/norm/commit/d6297ffc51c5ab5b84c6042bd322dba88bf468e1))
* pin codegen session zone to UTC instead of inheriting the host zone ([6a62c0c](https://github.com/pkware/norm/commit/6a62c0c61b184669d2af9695810b505ba008bcf3))

## [0.4.0](https://github.com/pkware/norm/compare/v0.3.0...v0.4.0) (2026-06-25)


### Features

* add Framework.MICRONAUT DI-only mode ([8dde298](https://github.com/pkware/norm/commit/8dde298f4c745a399cd0f7257f7754330a4ad9e7)), closes [#137](https://github.com/pkware/norm/issues/137)
* Queries interface extends Transactable in no-framework mode ([e082428](https://github.com/pkware/norm/commit/e082428025cde06e51b33400d651071b49b5d6af)), closes [#136](https://github.com/pkware/norm/issues/136)
* Switch to targeting Java 25 ([fa935db](https://github.com/pkware/norm/commit/fa935db989aa91e1bd07a69bae5c10b38540de88))


### Bug Fixes

* **deps:** split micronautTest version from micronaut core ([9e38fe7](https://github.com/pkware/norm/commit/9e38fe70da4c64cbdfbbf0fa8cf3a29e9c0cb9d2))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.7.0 ([717e9ee](https://github.com/pkware/norm/commit/717e9ee9bdcbfe6c61d818a5eb8752e61a4a4553))
* **deps:** update dependency io.micronaut.sql:micronaut-jdbc-hikari to v7.0.1 ([fa67872](https://github.com/pkware/norm/commit/fa67872c392dc0ad7b002ac93f1f3e74eb533ed0))
* **deps:** update dependency io.micronaut.test:micronaut-test-junit5 to v5 ([3197dab](https://github.com/pkware/norm/commit/3197dabcecfe06f421557aee0ee9b222d2f648bb))
* **deps:** update micronaut ([6ed664e](https://github.com/pkware/norm/commit/6ed664efb7f2ee9953b811ff515266f8b444b560))
* **deps:** update micronaut to v4.10.24 ([d2c6c65](https://github.com/pkware/norm/commit/d2c6c650706ecd311de4465bc1019d3724099b5d))
* **deps:** update micronaut to v4.10.25 ([df7470c](https://github.com/pkware/norm/commit/df7470c71b0050c1047c6dafcf479b7bad4d9b63))
* **deps:** update micronaut to v5 ([e108b66](https://github.com/pkware/norm/commit/e108b662b677effeda9fe5f125adcc24ea866885))
* **deps:** update micronaut to v5.1.0 ([e8b6281](https://github.com/pkware/norm/commit/e8b6281e14eaaa3774a2a014dc79422283ae80dc))
* **deps:** update micronaut to v5.1.1 ([5f46b7d](https://github.com/pkware/norm/commit/5f46b7dab1f394ecec13e9786965d4b65767230d))
* **deps:** update micronaut to v5.1.2 ([454ffe0](https://github.com/pkware/norm/commit/454ffe01b18a9b293a50117c21f5ac9acac1da53))
* **deps:** update micronautdata to v5 ([bdc0fbd](https://github.com/pkware/norm/commit/bdc0fbd9ca9d8c60bc65b5dd2e4824947c252b06))

## [0.3.0](https://github.com/pkware/norm/compare/v0.2.1...v0.3.0) (2026-05-28)


### ⚠ BREAKING CHANGES

* Generated code for timestamptz columns now uses java.time.Instant instead of java.time.OffsetDateTime.

### Features

* map timestamptz to Instant instead of OffsetDateTime ([a62b221](https://github.com/pkware/norm/commit/a62b221ca10f6ac571dff27141c660080e67d3e2))
* support directories for schema and query paths ([84c910a](https://github.com/pkware/norm/commit/84c910a85eb662206414303bb08e73b54aa385c8))


### Bug Fixes

* deduplicate reused named parameters in generated code ([379cb75](https://github.com/pkware/norm/commit/379cb75c4966c91c56fd20290abc3ae13ee97d75)), closes [#102](https://github.com/pkware/norm/issues/102)
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.5.1 ([94bcf86](https://github.com/pkware/norm/commit/94bcf86501295a39eb3fc7d32ad73a6ab2fc7675))
* **deps:** update dependency com.diffplug.spotless:spotless-plugin-gradle to v8.6.0 ([9ca4508](https://github.com/pkware/norm/commit/9ca45082d80546b36ed5fdef066ca726bedf9998))
* **deps:** update dependency org.junit.jupiter:junit-jupiter-params to v6.1.0 ([98bcc37](https://github.com/pkware/norm/commit/98bcc375921a3d68cabd1d6d3d037ea00384d8a9))
* disable parallel test execution for Micronaut e2e tests ([02573b1](https://github.com/pkware/norm/commit/02573b1e12115125463e6f5b7b95ee0f280eb07e))
* resolve compiler warning ([12b0be0](https://github.com/pkware/norm/commit/12b0be0f59aec6aaec085613b1635f37067a60fe))
* stop closing transaction-bound connection in Micronaut e2e tests ([4a934c1](https://github.com/pkware/norm/commit/4a934c196af5188541d6271d760655f95c07e18c))
* stop dumping raw pg_node_tree text to Gradle output ([bc5d3c7](https://github.com/pkware/norm/commit/bc5d3c7a6f88e04cc8a8767549957498b7dd0449)), closes [#74](https://github.com/pkware/norm/issues/74)
* use a fixed version of Norm ([2a98340](https://github.com/pkware/norm/commit/2a983402914887354541572471fd7c8ee40bdf57))
* use regular function types instead of extension functions in batch extractors ([c66517c](https://github.com/pkware/norm/commit/c66517ce7c6bc6198c3a12ff58b1e771283a7e42))

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
