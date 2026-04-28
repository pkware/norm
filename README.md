# Norm
Norm is Not an ORM. Norm is a SQL-first code generator for Postgres and Kotlin.

Norm generates Kotlin code from your SQL DDL and DML. This lets you write highly performant code in a great data
modification language, while getting fully type-safe and compile-time checked mapping code on the JVM.

Norm also provides a thin runtime, optimized for the Postgres JDBC driver, to make working with JDBC less error prone.

Norm strives to:
1. Be correct
2. Be performant
3. Give you the APIs needed to meet typical requirements
4. Be debuggable & readable

Norm does not:
- Have an entity model
- Integrate or compete with JPA
- Facilitate serialization (JSON, protobuf, etc)
- Support JVM-first database modeling

## Gradle
See the [Gradle plugin README](gradle-plugin/README.md) for setup details.

### How It Works

Norm uses JDBC metadata APIs to analyze your SQL. During the build, Norm will:
- Start a PostgreSQL container using Testcontainers
- Apply your schema files to the database
- Use JDBC `DatabaseMetaData` and `PreparedStatement` metadata to introspect types
- Generate fully type-safe Kotlin code for your queries
- Auto-generate CRUD methods (insert, find, exists, count, delete) for each table
- Stop the container

**Requirements:**
- Docker must be installed and running
- Internet connection (first run only, to pull PostgreSQL image)

**Customization:**

Override PostgreSQL version:
```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")
      postgresVersion.set("16") // Override default 18
    }
  }
}
```

## TODO
### After release
- [ ] Support batching from Stream, not just Iterable
- [ ] Make it easy to enforce named arguments for query methods. These are easy to change in SQL and have calling code compile incorrectly. Detekt, Intellij, etc.
- [ ] Add a getting started section to the README
- [ ] IntelliJ plugin for SQL fragment tracking. Currently we ship an `intellij-languageinjection.xml` config that provides basic SQL injection for `Query.append()`, but each fragment is analyzed in isolation. A proper plugin implementing `LanguageInjectionContributor` or using the `MultiHostInjector` API could track string values flowing through the builder pattern and reconstruct the full SQL statement for validation. This is how Hibernate and Spring's `JdbcClient` achieve fragment-aware SQL support.
- [ ] Support sqlc.embed() equivalent via SQL functions or table references
- [ ] RLS information in table and repository KDoc

## Background
ORMs like Hibernate take a code-first approach that doesn't sit well with the authors. It causes confusion as there are
effectively 2 sources of truth: the Java code and the actual database. Both must be kept in sync. Plus there are
well-known issues that arise with ORMs in terms of performance, nullability, etc.

Solutions like Spring Data or Micronaut Data ease the ORM pain significantly. However, they still require a dev to
manually map database types into Java by creating POJOs, adding annotations, and occasionally writing POJOs.

SQLDelight can be considered the spiritual predecessor to this project. Both Norm and SQLDelight are database-first and
generate code via Gradle plugin from database sources. However, SQLDelight struggles with Postgres syntax, so Norm was
created to be more Postgres-oriented.

sqlc is also database-first. There were a lot of things we liked about sqlc, but its code output fell short of the
developer experience we wanted, and it didn't integrate nicely into our build tooling. Norm originally used sqlc as its
foundation but has since replaced it with direct JDBC metadata analysis, eliminating the external binary dependency
and gaining full control over PostgreSQL type resolution.

## Developing
Load the `example` project into Intellij or use it as your Gradle entry point. See the [README](example/README.md) for
details.

## Releasing
Releases are automated via [release-please](https://github.com/googleapis/release-please). As `feat`
and `fix` commits accumulate on `main`, release-please maintains an open release PR that
bumps the version in `gradle.properties`. Merging that PR causes release-please to
push a semver tag, which triggers the publish workflow to sign and publish artifacts to Maven Central
and create a GitHub Release with auto-generated release notes.

Every push to `main` also publishes the current `-SNAPSHOT` version to the Maven Central snapshot
repository at `central.sonatype.com/repository/maven-snapshots/`.
