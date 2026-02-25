# CLAUDE.md

## Important: Read the READMEs

This project has multiple README files with important context:
- `README.md` - Project background, motivation, and design philosophy
- `gradle-plugin/README.md` - Plugin usage and configuration
- `runtime/README.md` - Runtime API documentation (if present)

**Always read relevant READMEs** when working on a module or feature to understand the intended design and constraints.

## Project Overview

Norm (Not an ORM) is a SQL-first code generator for Postgres and Kotlin. It generates type-safe Kotlin code from SQL DDL and DML, letting developers write performant SQL while getting compile-time checked mapping code.

## Related Projects

Understanding these projects is valuable when designing and implementing Norm features:

### SQLDelight (https://github.com/cashapp/sqldelight)

SQLDelight is the spiritual predecessor to Norm. Both are database-first and generate code via Gradle plugins. Norm was created because SQLDelight struggles with Postgres-specific syntax.

When working on Norm:
- SQLDelight's API design choices are worth studying for similar features
- Its Gradle plugin integration patterns informed Norm's approach
- Features SQLDelight supports well may be good candidates for Norm

## Module Structure

```
norm/
├── generator/                 # Code generator: JDBC analysis → Kotlin via KotlinPoet
├── gradle-plugin/             # Gradle plugin orchestrating container + analysis + generation
├── runtime/                   # Thin runtime library for JDBC operations
├── example/                   # Micronaut usage examples (composite build)
├── e2e-tests/                 # End-to-end tests (standalone, no framework)
├── e2e-tests-micronaut/       # Micronaut integration tests
├── e2e-tests-spring/          # Spring integration tests
├── proto/                     # Protocol buffer definitions (internal Wire types)
├── test-scenarios/            # Test scenarios with golden files
├── test-scenarios-frameworks/ # Framework-specific test scenarios
└── buildSrc/                  # Shared Gradle build logic
```

### Module Dependencies

- `gradle-plugin` → `generator` (uses generator to produce code)
- Generated user code → `runtime` (runtime is a dependency of generated code)

## Key Technologies

- **Kotlin** - Primary language
- **KotlinPoet** - Code generation library (in `generator`)
- **Wire** - Protocol buffer types used as internal model (from `proto/codegen.proto`)
- **Testcontainers** - Starts PostgreSQL for JDBC-based schema/query analysis
- **Gradle** - Build system with convention plugins in `buildSrc`

## Build & Test

Example Gradle tasks:

- `build`                     # Build all modules
- `test`                      # Run all tests
- `:generator:test`           # Test specific module

### Prerequisites

- JDK 17+
- Docker (for Testcontainers)

## Code Style

- **Indentation**: 2 spaces
- **Line length**: 120 characters max
- **Linting**: ktlint with IntelliJ IDEA style
- **Trailing newlines**: Required
- **Naming**: Use full, properly spelled words in identifiers. No informal abbreviations (e.g., `openParenthesis` not `openParen`, `parameter` not `param`, `expression` not `expr`). Standard well-known abbreviations like `sql`, `id`, `url` are fine.
- See `.editorconfig` for full details

## Architecture

### Code Generation Pipeline

1. **gradle-plugin** starts a PostgreSQL Testcontainer and applies schema SQL files
2. **JdbcAnalyzer** uses JDBC metadata APIs to build a `Catalog` (tables, columns, enums, domains) and analyze queries (parameter types, result column types)
3. **generator** takes the `Catalog` + analyzed `Query` objects (Wire proto types from `proto/codegen.proto`) and produces Kotlin via KotlinPoet
4. **gradle-plugin** writes the generated `.kt` files

### Runtime Library

The `runtime` module provides:
- `NormDriver` - Main entry point, wraps `ConnectionProvider`
- `ConnectionProvider` - Abstraction for obtaining JDBC connections (framework integration point)
- `BorrowedConnection` - Extended-lifecycle connection for lazy streaming
- `Query<T>` - Dynamic query builder with parameter binding
- `Many<T>` - Terminal operations for multi-row results

### Generated Code Pattern

For each SQL file, Norm generates:
- An **interface** with query methods (e.g., `Queries`)
- An **implementation** taking `ConnectionProvider` (e.g., `PostgresQueries`)
- **Data classes** for result types (Java records when possible)

When a framework is configured (`frameworks` property), Norm also generates:
- DI annotations on `PostgresQueries` (`@Singleton` for Micronaut, `@Component` for Spring)
- A framework-specific `ConnectionProvider` implementation (e.g., `MicronautConnectionProvider`)
- `@Requires(missingBeans)` escape hatches for Micronaut (users can override generated beans)

## SQL Conventions

Queries use annotation comments:
```sql
-- name: getAuthorByName :one
SELECT * FROM author WHERE name = ?;

-- name: listAuthors :many
SELECT * FROM author;

-- name: addAuthor :execrows
INSERT INTO author(name, email) VALUES (?, ?);
```

Commands: `:one` (single result), `:many` (multiple results), `:execrows` (returns affected row count)

## Key Files

- `generator/src/main/kotlin/norm/generator/JdbcAnalyzer.kt` - JDBC-based schema and query analysis
- `generator/src/main/kotlin/norm/generator/QueryFileParser.kt` - Parses `-- name: X :cmd` annotations from SQL
- `generator/src/main/kotlin/norm/generator/InterfaceBuilder.kt` - Generates query interfaces
- `generator/src/main/kotlin/norm/generator/ImplementationBuilder.kt` - Generates implementations
- `gradle-plugin/src/main/kotlin/norm/gradle/NormGenerateTask.kt` - Gradle task orchestrating the pipeline
- `runtime/src/main/kotlin/norm/NormDriver.kt` - Core runtime driver
- `runtime/src/main/kotlin/norm/Query.kt` - Dynamic query API
- `proto/codegen.proto` - Wire proto definitions for internal model types

## Testing

- Generator tests use Testcontainers to run the full pipeline (JDBC analysis → code generation) and compare against golden files
- Runtime tests use Mockito for JDBC mocking
- Gradle plugin tests use Gradle TestKit for integration testing

### Golden Files

Test scenarios in `test-scenarios-*/` contain inputs (`schema.sql`, `queries.sql`) and expected outputs (`example/*.kt`).

To regenerate golden files after changing the generator:
- All scenarios: `./gradlew :gradle-plugin:generateGoldenFiles`
- Single scenario: `./gradlew :gradle-plugin:generateGoldenFiles -Pscenario=<name>`

The task captures generator output even if it doesn't compile, enabling iterative development.

## IntelliJ Integration

- SQL language injection configured via `runtime/src/main/resources/META-INF/intellij-languageinjection.xml`
- `@Language("PostgreSQL")` annotations used throughout for IDE SQL support

## Documentation
- Always document things that are optional/nullable. Explain what `null` means and/or the implications of it.
- In KDoc, use backticks for literals like `null` and numbers.
