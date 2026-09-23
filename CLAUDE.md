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
3. **generator** takes the `Catalog` + analyzed `Query` objects (model data classes in `generator/src/main/kotlin/norm/generator/Model.kt`) and produces Kotlin via KotlinPoet
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

By default, Norm also auto-generates CRUD methods (insert, find, exists, count, delete) for each non-view table. These are synthesized as `ParsedQuery` objects by `CrudQuerySynthesizer`, merged with user queries (user queries win on name conflicts), and fed through the same analysis pipeline. Disable with `generateCrud = false`.

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
- `generator/src/main/kotlin/norm/generator/CrudQuerySynthesizer.kt` - Synthesizes CRUD queries from catalog tables
- `generator/src/main/kotlin/norm/generator/InterfaceBuilder.kt` - Generates query interfaces
- `generator/src/main/kotlin/norm/generator/ImplementationBuilder.kt` - Generates implementations
- `gradle-plugin/src/main/kotlin/norm/gradle/NormGenerateTask.kt` - Gradle task orchestrating the pipeline
- `runtime/src/main/kotlin/norm/NormDriver.kt` - Core runtime driver
- `runtime/src/main/kotlin/norm/Query.kt` - Dynamic query API
- `generator/src/main/kotlin/norm/generator/Model.kt` - Model data classes for internal model types

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

Documentation is reviewed as strictly as code. A comment that adds noise, reads as machine-generated, or is written for the wrong reader is a defect — it blocks the PR until fixed, the same as a failing test. Getting it wrong costs a review round-trip and makes the change look careless.

A reviewer rejects a comment that:
- reads as AI-generated filler — hedging, restating the signature, explaining the obvious.
- narrates a decision log or ADR ("we chose X because…"). Rationale belongs in the commit message.
- addresses whoever is reading the diff today ("as discussed", "note that we…", "for now") instead of a stranger reading it in a year with none of that context.
- rambles. One precise sentence beats a paragraph.

Write for that stranger. State what they need; stop.

**Banned phrasings.** These read as machine-generated, in KDoc and inline comments alike, in production and test code. Each is a review-blocking defect on its own:

- Cleft constructions — "which is what releases it to the reaper", "the group is exactly what routes together". Write the plain verb: "releasing it to the reaper", "everything in a group routes to the same queue".
- Invented collocations — "settlement is unconditional on the outcome" (write "does not depend on"), "skipped rather than thrown on" (write "instead of throwing"), "progress reads over a scan" (write "reads the progress of a scan"), hyphenated coinages like "findings-yielding fixture".
- An appositive fragment as the opening sentence — "Never invoked, in place of [Foo]." Open with a subject and a verb: "Stands in for [Foo] and is never invoked."
- Emphatic absolutes standing in for a fact — "re-derived forever", "the one execution that ever scans that group".
- Capitals or asterisks for emphasis — `REAL`, `OUT OF SCOPE`, `*visible*`. A sentence that needs shouting is the wrong sentence.
- A metaphor in place of the mechanism — "the backlog is the only door into work" (write "all work enters through the backlog").
- Words anchored to the moment of writing — "this cycle", "for now", "currently", "as discussed".

**Structural tells.** Word choice is not the only giveaway; sentence shape is. Rewrite a comment built on any of these:

- Claim, colon, argument for the claim — "Batched rather than one call per entry: the statements travel together."
- Contrastive framing that defines the code by what it is not — "X instead of Y", "rather than a dev-only path". Say what it does.
- The non-action first — "Nothing is dispatched here." Name the thing that does dispatch.
- Three parallel verbs or noun phrases in a row — "claims the entries, resolves the routing, and starts the scan."
- A fronted adverbial that delays the subject — "Against the dev S3 datastore the keys are corpus objects."
- One sentence carrying more than one claim. Over about 25 words with stacked subordinate clauses, split it or cut it.

**Recurrence outranks any single sentence.** Three or more comments in one change built on the same template is generated prose, even where each reads acceptably alone. Vary the shape or delete the comment. Read a change's comments as one corpus rather than hunk by hunk; the frequency is the signal, and a per-sentence check cannot see it.

**General rules**
- End all documentation fragments with punctuation (typically a period).
- Do not document obvious things. Avoid noise.
- **Be concise.** Prefer short, direct statements over verbose explanations. Example: "Immutable." instead of "Enforced by triggers to be database-generated only (no manual assignment) and immutable."
- Focus on why, not what. We want to document decisions.
- Use backticks for literals like `null`, `true`, `false`.

**KDoc linking:**
- Use KDoc's linking syntax when referencing other classes and members: `[ClassName]` or `[packageName.ClassName]`
- NEVER use fully qualified names in links. Use imports.
- Link syntax examples:
  - `[Organization]` - references a class in scope
  - `[recurse.onboarding.Organization]` - fully qualified reference - BAD
  - `[Organization.name]` - references a property
  - `[findOrganization]` - references a function
- These links become clickable in IDEs and generated documentation

**Function documentation:**
- Document what exceptions are thrown and under what conditions.
- Use `@Throws` annotation in addition to prose documentation.
- For nullable parameters/returns, document what `null` means, how it occurs, and how it will be treated.

```kotlin
/**
 * Retrieves a customer by ID.
 *
 * @param customerId the unique identifier of the customer.
 * @return the customer if found, or `null` if no customer exists with the given ID.
 *         `null` is treated as "not found" and should be handled by returning a 404.
 * @throws DatabaseException if the database connection fails.
 */
@Throws(DatabaseException::class)
fun findCustomer(customerId: String): Customer?
```

**Class documentation:**
- Use `@param` for constructor parameters, **not** `@property`.
- Entity/data classes should document what each field represents.

```kotlin
/**
 * Represents a customer in the system.
 *
 * @param id unique identifier, generated by the database.
 * @param name customer's display name, must be non-blank.
 * @param createdAt timestamp when the customer record was created, never `null`.
 * @param deletedAt timestamp when the customer was soft-deleted, `null` if active.
 */
data class Customer(
  val id: UUID,
  val name: String,
  val createdAt: Instant,
  val deletedAt: Instant?
)
```

**Section markers:** Never add divider comments like `// Request DTOs`, `// Response DTOs`, `// Helper functions`. They go stale as code evolves. Make organization self-evident through file structure and naming; if a marker feels needed, split the code into separate files.
