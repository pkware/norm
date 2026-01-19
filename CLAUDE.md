# CLAUDE.md

## Important: Read the READMEs

This project has multiple README files with important context:
- `README.md` - Project background, motivation, and design philosophy
- `gradle-plugin/README.md` - Plugin usage, sqlc version requirements
- `runtime/README.md` - Runtime API documentation (if present)

**Always read relevant READMEs** when working on a module or feature to understand the intended design and constraints.

## Project Overview

NORM (Not an ORM) is a SQL-first code generator for Postgres and Kotlin. It generates type-safe Kotlin code from SQL DDL and DML, letting developers write performant SQL while getting compile-time checked mapping code.

## Related Projects

Understanding these projects is valuable when designing and implementing NORM features:

### sqlc (https://sqlc.dev)

NORM uses sqlc as its foundation. sqlc parses SQL files and schema, then outputs structured metadata (queries, parameters, column types) that NORM's generator consumes. The protocol is defined in `proto/codegen.proto`.

When working on NORM:
- sqlc's output format determines what information is available to the generator
- sqlc documentation explains the `-- name: queryName :command` annotation syntax
- Limitations in sqlc's Postgres support may affect what NORM can do

### SQLDelight (https://github.com/cashapp/sqldelight)

SQLDelight is the spiritual predecessor to NORM. Both are database-first and generate code via Gradle plugins. NORM was created because SQLDelight struggles with Postgres-specific syntax.

When working on NORM:
- SQLDelight's API design choices are worth studying for similar features
- Its Gradle plugin integration patterns informed NORM's approach
- Features SQLDelight supports well may be good candidates for NORM

## Module Structure

```
norm/
├── generator/       # Code generator that produces Kotlin from sqlc output
├── gradle-plugin/   # Gradle plugin wrapping sqlc + generator
├── runtime/         # Thin runtime library for JDBC operations
├── example/         # Usage examples
├── proto/           # Protocol buffer definitions (sqlc plugin interface)
├── test-scenarios/  # Generated code test fixtures
└── buildSrc/        # Shared Gradle build logic
```

### Module Dependencies

- `gradle-plugin` → `generator` (uses generator to produce code)
- Generated user code → `runtime` (runtime is a dependency of generated code)

## Key Technologies

- **Kotlin** - Primary language
- **KotlinPoet** - Code generation library (in `generator`)
- **Wire** - Protocol buffer implementation for sqlc communication
- **sqlc** - External tool that parses SQL and provides schema/query metadata
- **Gradle** - Build system with convention plugins in `buildSrc`

## Build & Test

Example Gradle tasks:

- `build`                     # Build all modules
- `test`                      # Run all tests
- `:generator:test`           # Test specific module

### Prerequisites

- JDK 17+
- sqlc installed (see gradle-plugin/README.md for version requirements)

## Code Style

- **Indentation**: 2 spaces
- **Line length**: 120 characters max
- **Linting**: ktlint with IntelliJ IDEA style
- **Trailing newlines**: Required
- See `.editorconfig` for full details

## Architecture

### Code Generation Pipeline

1. **sqlc** parses SQL files and schema, outputs JSON matching `proto/codegen.proto`
2. **generator** reads sqlc JSON, produces Kotlin via KotlinPoet
3. **gradle-plugin** orchestrates: runs sqlc → feeds output to generator → writes `.kt` files

### Runtime Library

The `runtime` module provides:
- `NormDriver` - Main entry point, wraps DataSource
- `Query<T>` - Dynamic query builder with parameter binding
- `Many<T>` - Terminal operations for multi-row results
- `Transacter` / `Transaction` - Transaction management

### Generated Code Pattern

For each SQL file, NORM generates:
- An **interface** with query methods (e.g., `PostgresQueries`)
- An **implementation** using `NormDriver`
- **Data classes** for result types (Java records when possible)

## SQL Conventions

Queries use sqlc annotation format:
```sql
-- name: getAuthorByName :one
SELECT * FROM author WHERE name = $1;

-- name: listAuthors :many
SELECT * FROM author;

-- name: addAuthor :execrows
INSERT INTO author(name, email) VALUES ($1, $2);
```

Commands: `:one` (single result), `:many` (multiple results), `:execrows` (returns affected row count)

## Key Files

- `generator/src/main/kotlin/norm/generator/InterfaceBuilder.kt` - Generates query interfaces
- `generator/src/main/kotlin/norm/generator/ImplementationBuilder.kt` - Generates implementations
- `runtime/src/main/kotlin/norm/NormDriver.kt` - Core runtime driver
- `runtime/src/main/kotlin/norm/Query.kt` - Dynamic query API
- `proto/codegen.proto` - sqlc plugin protocol definition

## Testing

- Generator tests use JSON fixtures in `test-scenarios/`
- Runtime tests use Mockito for JDBC mocking
- Gradle plugin tests use Gradle TestKit

### Golden Files

Test scenarios in `test-scenarios/` contain inputs (`schema.sql`, `queries.sql`) and expected outputs (`schema.json`, `example/*.kt`).

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
