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
- Generate fully type-safe Kotlin code
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
- [ ] Enum adapter. Map enums to String and back by default.
- [ ] List/array/stream/iterable adapter. If given one of these, just convert it to an array.
- [ ] Value class adapter (inline classes)
- [ ] Support batching from Stream, not just Iterable
- [ ] Make it easy to enforce named arguments for query methods. These are easy to change in SQL and have calling code compile incorrectly. Detekt, Intellij, etc.
- [ ] JSON/JSONB to Java struct adapter. More broadly, custom type adapters.
- [ ] Add a getting started section to the README
- [ ] IntelliJ plugin for SQL fragment tracking. Currently we ship an `intellij-languageinjection.xml` config that provides basic SQL injection for `Query.append()`, but each fragment is analyzed in isolation. A proper plugin implementing `LanguageInjectionContributor` or using the `MultiHostInjector` API could track string values flowing through the builder pattern and reconstruct the full SQL statement for validation. This is how Hibernate and Spring's `JdbcClient` achieve fragment-aware SQL support.
- [ ] It would be nice for query method Javadocs to have the SQL that they'll execute in them.
- [ ] Support sqlc.embed() equivalent via SQL functions or table references

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

### Releasing

1. Change the relevant version in `gradle.properties` to a non-SNAPSHOT version.
2. `git commit -am "Release version X.Y.Z."` (where and X.Y.Z is the new version)
3. Push or merge to the main branch.
4. Update `gradle.properties` to the next SNAPSHOT version.
5. `git commit -am "Prepare next development version."`
6. After the merge, tag the release commit on the main branch. `git tag -a X.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
7. `git push --tags`.
