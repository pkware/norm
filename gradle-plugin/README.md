# Gradle plugin

This plugin requires you have [sqlc] installed.

On Mac and Linux, any recent version will do.

On Windows, you must have version [1.25], as anything newer is [bugged].
Extract the binary to `C:\Program Files\sqlc\sqlc.exe`.

## Development

### Generating Golden Files

Test scenarios in [test-scenarios](../test-scenarios) contain golden files (expected generator output).
To regenerate them after changing the generator:

```bash
# Generate all scenarios
./gradlew :gradle-plugin:generateGoldenFiles

# Generate a specific scenario
./gradlew :gradle-plugin:generateGoldenFiles -Pscenario=basic_embeds
```

The task runs the generator and copies output to [test-scenarios](../test-scenarios), regardless of
whether the generated code compiles. This allows iterative development.

[sqlc]: https://docs.sqlc.dev/en/latest/overview/install.html
[1.25]: https://github.com/sqlc-dev/sqlc/releases/download/v1.25.0/sqlc_1.25.0_windows_amd64.zip
[bugged]: https://github.com/sqlc-dev/sqlc/issues/3612

## Configuration

### Basic Configuration (with database-powered type resolution on enabled default)

```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")
      // Testcontainers enabled by default with PostgreSQL 18
    }
  }
}
```

### Configuration Properties

**Property: `useDatabase`**
- **Type:** `Property<Boolean>`
- **Default:** `true` (database-backed analysis enabled)
- **Effect:** When `true`, starts a PostgreSQL container (via Testcontainers) for enhanced sqlc validation
- **Requires:** Docker installed and running

**Property: `postgresVersion`**
- **Type:** `Property<String>`
- **Default:** `"18"` (latest stable at implementation time)
- **Valid values:** PostgreSQL version tags (e.g., "16", "15", "14", "16.1-alpine")
- **Effect:** Overrides the default PostgreSQL version for the database container

### Database-Backed Query Analysis (Enabled by Default)

**Norm now uses Testcontainers by default** for enhanced query validation. On your first build, Norm will:
- Start a PostgreSQL 18 container using Testcontainers
- Apply your schema files to the database
- Provide database connection to sqlc for enhanced type resolution
- Enable sqlc to properly handle Postgres domains, enums, and extensions
- Automatically stop the container when build completes

**No configuration required** - it just works out of the box!

**Requirements:**
- Docker must be installed and running
- Internet connection (first run only, to pull PostgreSQL image)

**Performance:**
- First run: ~10-20 seconds to pull Docker image (cached afterwards)
- Subsequent runs: ~2-5 seconds to start container + apply schemas
- Container is created fresh for each task execution (not shared across builds)
- Schema application is always idempotent (fresh database each run)
- Container automatically stops after task completes

**Container Lifecycle:**

Norm creates a fresh PostgreSQL container for each task execution:
- Container starts when `normRunSqlc` task begins
- Schemas are applied to the fresh database
- sqlc connects and generates type information
- Container stops when task completes

This ensures **idempotent builds** - you always get the same result regardless of previous state. No manual cleanup needed.

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

**Disable database (faster builds, less validation):**
```kotlin
norm {
  databases {
    register("example") {
      packageName.set("example")
      schemas.add("src/main/sql/schema.sql")
      queries.add("src/main/sql/queries.sql")
      useDatabase.set(false) // Disable database-backed analysis
    }
  }
}
```

### Multi-Database Projects

Each database can independently configure database usage:

```kotlin
norm {
  databases {
    // Fast build: schema-only validation
    register("simple") {
      packageName.set("com.example.simple")
      schemas.add("src/main/sql/simple-schema.sql")
      queries.add("src/main/sql/simple-queries.sql")
      useDatabase.set(false) // Opt out for speed
    }

    // Default: database-backed with PostgreSQL 18
    register("standard") {
      packageName.set("com.example.standard")
      schemas.add("src/main/sql/standard-schema.sql")
      queries.add("src/main/sql/standard-queries.sql")
      // Uses defaults: useDatabase=true, postgresVersion=18
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
