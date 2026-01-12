# NORM
NORM is Not an ORM. NORM is a SQL-first code generator for Postgres and Kotlin.

NORM generates Kotlin code from your SQL DDL and DML. This lets you write highly performant code in a great data
modification language, while getting fully type-safe and compile-time checked mapping code on the JVM.

NORM also provides a thin runtime, optimized for the Postgres JDBC driver, to make working with JDBC less error prone.

NORM strives to:
1. Be correct
2. Be performant
3. Give you the APIs needed to meet typical requirements
4. Be debuggable & readable

NORM does not:
- Have an entity model
- Integrate or compete with JPA
- Facilitate serialization (JSON, protobuf, etc)
- Support JVM-first database modeling

## Gradle
See the [Gradle plugin README](gradle-plugin/README.md) for setup details.

## TODO
### Before release
- [ ] Gradle plugin: connect to a real DB
- [ ] Support the timestamp type
- [ ] Pass detekt
- [ ] Delete old generated files when no longer needed
- [ ] Framework support
- [ ] Read/write transactions

### After release
- [ ] Enum adapter. Map enums to String and back by default.
- [ ] List/array/stream/iterable adapter. If given one of these, just convert it to an array.
- [ ] Value class adapter (inline classes)
- [ ] Different behavior for read & write transactions
- [ ] Use save-points for nested transactions—the new code has a model for this already.
- [ ] ScopedValue instead of ThreadLocal? Requires Java 25+
- [ ] Populate KDoc using Postgres COMMENTs.
- [ ] Support batching from Stream, not just Iterable
- [ ] Make it easy to enforce named arguments for query methods. These are easy to change in SQL and have calling code compile incorrectly. Detekt, Intellij, etc.
- [ ] JSON/JSONB to Java struct adapter. More broadly, custom type adapters.
- [ ] Add a getting started section to the README
- [ ] IntelliJ plugin for SQL fragment tracking. Currently we ship an `intellij-languageinjection.xml` config that provides basic SQL injection for `Query.append()`, but each fragment is analyzed in isolation. A proper plugin implementing `LanguageInjectionContributor` or using the `MultiHostInjector` API could track string values flowing through the builder pattern and reconstruct the full SQL statement for validation. This is how Hibernate and Spring's `JdbcClient` achieve fragment-aware SQL support.
- [ ] It would be nice for query method Javadocs to have the SQL that they'll execute in them.

## Background
ORMs like Hibernate take a code-first approach that doesn't sit well with the authors. It causes confusion as there are
effectively 2 sources of truth: the Java code and the actual database. Both must be kept in sync. Plus there are
well-known issues that arise with ORMs in terms of performance, nullability, etc.

Solutions like Spring Data or Micronaut Data ease the ORM pain significantly. However, they still require a dev to
manually map database types into Java by creating POJOs, adding annotations, and occasionally writing POJOs.

SQLDelight can be considered the spiritual predecessor to this project. Both NORM and SQLDelight are database-first and
generate code via Gradle plugin from database sources. However, SQLDelight struggles with Postgres syntax, so NORM was
created to be more Postgres-oriented.

sqlc is also database-first. There were a lot of things we liked about sqlc, but 2 things stuck out as limits for us.
The first was that the code produced by the native Kotlin support it has fell short of the developer experience we
wanted. It didn't add enough safety on queries and didn't lend itself well to additional features. The second was that
it didn't integrate nicely into our build tooling. We wanted a Gradle integration so devs can use the tooling they are
familiar with. However, sqlc does so much well that we continue to use it as the foundation of NORM.

## Developing
Load the `example` project into Intellij or use it as your Gradle entry point. See the [README](example/README.md) for
details.
