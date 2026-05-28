# Gradle plugin

## Requirements

- **Docker** must be installed and running (Testcontainers is used to start a PostgreSQL instance)
- **JDK 17+**
- **A PostgreSQL-compatible JDBC driver** on the project's runtime classpath (e.g., `org.postgresql:postgresql` or the [AWS JDBC Driver for PostgreSQL](https://github.com/awslabs/aws-postgresql-jdbc))

## Development

### Generating Golden Files

Test scenarios in [test-scenarios](../test-scenarios) and [test-scenarios-frameworks](../test-scenarios-frameworks)
contain golden files (expected generator output).
To regenerate them after changing the generator:

```bash
# Generate all scenarios
./gradlew :gradle-plugin:generateGoldenFiles

# Generate a specific scenario
./gradlew :gradle-plugin:generateGoldenFiles -Pscenario=all_types
```

The task runs the generator and copies output to test-scenarios directories, regardless of
whether the generated code compiles. This allows iterative development.

## Configuration

### Basic Configuration

```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")
    }
  }
}
```

### Directory-based Configuration

When a path points to a directory, Norm includes all `*.sql` files directly inside it
(non-recursive), applied in lexicographic order by filename. This works well with migration
tools like Flyway or Liquibase where schemas are a folder of ordered migration files.

```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/resources/db/migration") // all *.sql files in this directory
      queries.add("src/main/sql/queries.sql")
    }
  }
}
```

You can mix individual files and directories:

```kotlin
schemas.add("src/main/sql/base-schema.sql")
schemas.add("src/main/resources/db/migration") // additional migrations from a directory
```

### Configuration Properties

**Property: `postgresVersion`**
- **Type:** `Property<String>`
- **Default:** `"18"`
- **Valid values:** PostgreSQL version tags (e.g., "16", "15", "14", "16.1-alpine")
- **Effect:** Overrides the default PostgreSQL version for the database container

**Property: `frameworks`**
- **Type:** `SetProperty<Framework>`
- **Default:** empty (no DI wiring)
- **Valid values:** `Framework.MICRONAUT_DATA`, `Framework.SPRING_DATA`
- **Effect:** When set, the generator produces framework-ready code:
  - **DI registration:** `PostgresQueries` is annotated with `@Singleton` (Micronaut) or `@Component` (Spring), so it auto-registers in the DI container.
  - **ConnectionProvider:** A framework-specific `ConnectionProvider` implementation is generated (`MicronautConnectionProvider` or `SpringConnectionProvider`) that bridges the framework's connection management to Norm, enabling `@Transactional` support.
  - **Override escape hatch (Micronaut):** Generated beans use `@Requires(missingBeans = [...])`, so providing your own `Queries` or `ConnectionProvider` bean disables the generated one.
  - When empty, no DI wiring is generated.

**Property: `generateCrud`**
- **Type:** `Property<Boolean>`
- **Default:** `true`
- **Effect:** When `true`, Norm auto-generates repository-style CRUD methods for every non-view table in the schema:
  - `insert<Table>(...)` — inserts a row, excluding auto-increment, server-default, and generated columns from parameters. Returns the excluded columns via `RETURNING` when applicable.
  - `find<Table>By<PkColumns>(...)` — selects by primary key (requires PK). The suffix is derived from actual PK column names, e.g. `findAuthorById`, `findSettingByKey`, `findOrderItemByOrderIdAndItemId`.
  - `exists<Table>By<PkColumns>(...)` — checks existence by primary key (requires PK)
  - `findAll<Table>()` — selects all rows
  - `count<Table>()` — counts all rows
  - `delete<Table>By<PkColumns>(...)` — deletes by primary key (requires PK)
  - `deleteAll<Table>()` — deletes all rows
- **Conflict resolution:** If a user-written query has the same name as a synthesized CRUD method, the user query takes priority and the CRUD method is skipped.
- **Disabling:** Set `generateCrud = false` to disable CRUD generation entirely.

**Property: `typeMappings`**
- **Type:** DSL block / `ListProperty<TypeMapping>`
- **Default:** empty (use Norm's built-in type mappings)
- **Effect:** Override how specific Postgres types (or individual columns) map to Kotlin types in generated code.

Norm auto-generates adapters for Postgres enum types (→ Kotlin `enum class`) and domain types (→ `@JvmInline value class`). `typeMappings` lets you substitute your own Kotlin type and `ColumnAdapter` implementation whenever those defaults don't fit — for example, mapping a `jsonb` column to a custom data class, or representing an enum as a sealed class.

#### Type-level overrides

A type-level override applies to **every column** of that Postgres type across all tables, including **array columns** of that type. It also **suppresses Norm's auto-generation** of the corresponding enum or domain class — you're taking full ownership of the type.

```kotlin
norm {
  databases {
    register("main") {
      packageName.set("com.example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")

      typeMappings {
        // Map the Postgres "mood" enum to a hand-written Kotlin enum.
        // Norm will NOT generate Mood.kt or MoodAdapter.kt.
        // Applies to both `mood` columns and `mood[]` array columns.
        type("mood") mapTo "com.example.CustomMood" using "com.example.CustomMoodAdapter"

        // Map all jsonb columns to a wrapper type.
        // Applies to both `jsonb` columns and `jsonb[]` array columns.
        type("jsonb") mapTo "com.example.JsonData" using "com.example.JsonDataAdapter"
      }
    }
  }
}
```

For a schema with both scalar and array columns:

```sql
CREATE TABLE users (
  current_mood  mood    NOT NULL,  -- scalar: CustomMood
  past_moods    mood[],            -- array:  Array<CustomMood?>?
  metadata      jsonb   NOT NULL,  -- scalar: JsonData
  tag_list      jsonb[]            -- array:  Array<JsonData?>?
);
```

No extra configuration is needed for the array columns — the same adapter is applied per element. Array elements are always nullable (`Array<CustomMood?>`) because Postgres arrays can contain `NULL` values regardless of the column's `NOT NULL` constraint. The column-level nullability controls whether the array *itself* is nullable.

#### Column-level overrides

A column-level override applies only to one specific column. It takes **precedence over any type-level override** for the same column. Norm still generates the type-level enum/domain adapter for other columns of that type. Column-level overrides also work for array columns.

```kotlin
typeMappings {
  // users.preferences uses UserPreferences, even though jsonb is mapped to JsonData above.
  column("users", "preferences") mapTo "com.example.UserPreferences" using "com.example.UserPreferencesAdapter"

  // Override a specific array column independently.
  column("users", "past_moods") mapTo "com.example.LegacyMood" using "com.example.LegacyMoodAdapter"
}
```

#### Adapter injection

Each user-configured mapping becomes a **constructor parameter on `PostgresQueries` without a default value**, so you must supply an instance. Auto-generated adapters (for enums and domains not overridden by `typeMappings`) have default values and can be omitted.

**Parameter naming convention:**

| Override kind | Naming rule | Example |
|---|---|---|
| Type-level | `${postgresType}Adapter` | `type("mood")` → `moodAdapter`, `type("jsonb")` → `jsonbAdapter` |
| Column-level | `${table}${Column}Adapter` | `column("users", "preferences")` → `usersPreferencesAdapter` |
| Auto-generated enum | `${enumType}Adapter` | Postgres `mood` enum → `moodAdapter` |
| Auto-generated domain | `${domainType}Adapter` | Postgres `email` domain → `emailAdapter` |

Names are derived from the **Postgres type/column name** (not the Kotlin class name), converted to camelCase.

```kotlin
val queries = PostgresQueries(
  connectionProvider = connectionProvider,
  jsonbAdapter = JsonDataAdapter(),        // type("jsonb") → jsonbAdapter
  moodAdapter = CustomMoodAdapter(),       // type("mood") → moodAdapter
  usersPreferencesAdapter = UserPreferencesAdapter(), // column("users", "preferences") → usersPreferencesAdapter
  // Auto-generated adapter — has a default, can be omitted
  // emailAdapter = EmailAdapter(),
)
```

When a `frameworks` setting is present, the DI container satisfies these dependencies automatically. Provide your adapter as a `@Singleton` (Micronaut) or `@Bean` (Spring) and it will be injected into `PostgresQueries`.

#### Writing a `ColumnAdapter`

```kotlin
class CustomMoodAdapter : ColumnAdapter<CustomMood, String> {
  override fun decode(databaseValue: String): CustomMood = when (databaseValue) {
    "happy" -> CustomMood.HAPPY
    "sad"   -> CustomMood.SAD
    else    -> error("Unknown mood: $databaseValue")
  }
  override fun encode(value: CustomMood): String = when (value) {
    CustomMood.HAPPY -> "happy"
    CustomMood.SAD   -> "sad"
  }
}
```

The type parameters are `ColumnAdapter<ApplicationType, DatabaseType>` where `DatabaseType` is the Kotlin type Norm uses to read/write the column over JDBC (typically `String` for text-backed types, `Int` for integer-backed domains, etc.).

### How It Works

Norm uses direct JDBC metadata analysis to generate type-safe Kotlin code:
1. Starts a PostgreSQL container using Testcontainers
2. Applies your schema files to the database
3. Uses `DatabaseMetaData` to introspect tables, columns, enums, and domains
4. Uses `PreparedStatement.getMetaData()` and `PreparedStatement.getParameterMetaData()` to analyze queries
5. Generates Kotlin code via KotlinPoet
6. Stops the container

**Performance:**
- First run: ~10-20 seconds to pull Docker image (cached afterwards)
- Subsequent runs: ~2-5 seconds to start container + apply schemas
- Container is created fresh for each task execution (not shared across builds)
- Container automatically stops after task completes

### Customization Examples

**Override PostgreSQL version:**
```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")
      postgresVersion.set("16") // Use PostgreSQL 16 instead of 18
    }
  }
}
```

### Multi-Database Projects

```kotlin
norm {
  databases {
    register("standard") {
      packageName.set("com.example.standard")
      schemas.add("src/main/sql/standard-schema.sql")
      queries.add("src/main/sql/standard-queries.sql")
    }

    // Custom version: database-backed with PostgreSQL 16
    register("legacy") {
      packageName.set("com.example.legacy")
      schemas.add("src/main/sql/legacy-schema.sql")
      queries.add("src/main/sql/legacy-queries.sql")
      postgresVersion.set("16") // Different version for compatibility
    }
  }
}
```

## IntelliJ IDEA Integration

When you first import a Norm-enabled project into IntelliJ IDEA, generated sources don't exist yet.
This causes unresolved-reference errors (red squiggles) until you run a build. Norm provides two
mechanisms to improve this experience.

### Automatic Generation on Sync (`gradle-idea-ext`)

Apply the [gradle-idea-ext](https://github.com/JetBrains/gradle-idea-ext) plugin to your **root**
project. Norm will automatically register its generation tasks to run after every IntelliJ Gradle
sync, including first import.

```kotlin
// settings.gradle.kts
plugins {
  id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.9" apply false
}

// build.gradle.kts (root project)
plugins {
  id("org.jetbrains.gradle.plugin.idea-ext")
}
```

No additional configuration is needed — Norm detects the plugin and wires `afterSync` automatically.

### Kotlin 2.3.0+ `generatedKotlin` API

When your project uses Kotlin 2.3.0 or later, Norm registers its output via the `generatedKotlin`
source directory API. This tells the Kotlin toolchain that Norm's output is generated code, enabling
future IntelliJ support for triggering generation during sync. No configuration is needed; Norm
detects the API automatically.
