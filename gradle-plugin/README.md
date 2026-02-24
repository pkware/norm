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
