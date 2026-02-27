package com.example

import jakarta.inject.Singleton
import norm.ColumnAdapter

/**
 * User-configured adapter that maps Postgres `jsonb` columns to [JsonData].
 *
 * Annotated with `@Singleton` so Micronaut automatically injects it into the generated
 * `PostgresQueries` constructor. User-configured adapters have no default value in the constructor,
 * so a bean must be present in the DI context.
 */
@Singleton
class JsonDataAdapter : ColumnAdapter<JsonData, String> {
  override fun decode(databaseValue: String): JsonData = JsonData(databaseValue)

  override fun encode(value: JsonData): String = value.raw
}
