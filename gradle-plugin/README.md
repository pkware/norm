# Gradle plugin

## Requirements

- **Docker** must be installed and running (Testcontainers is used to start a PostgreSQL instance)
- **JDK 17+**

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
  - `find<Table>ById(...)` — selects by primary key (requires PK)
  - `exists<Table>ById(...)` — checks existence by primary key (requires PK)
  - `findAll<Table>()` — selects all rows
  - `count<Table>()` — counts all rows
  - `delete<Table>ById(...)` — deletes by primary key (requires PK)
  - `deleteAll<Table>()` — deletes all rows
- **Conflict resolution:** If a user-written query has the same name as a synthesized CRUD method, the user query takes priority and the CRUD method is skipped.
- **Disabling:** Set `generateCrud = false` to disable CRUD generation entirely.

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
