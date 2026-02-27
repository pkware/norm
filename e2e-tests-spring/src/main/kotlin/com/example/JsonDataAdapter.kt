package com.example

import norm.ColumnAdapter
import org.springframework.stereotype.Component

/**
 * User-configured adapter that maps Postgres `jsonb` columns to [JsonData].
 *
 * Annotated with `@Component` so Spring automatically injects it into the generated
 * `PostgresQueries` constructor. User-configured adapters have no default value in the constructor,
 * so a bean must be present in the DI context.
 */
@Component
class JsonDataAdapter : ColumnAdapter<JsonData, String> {
  override fun decode(databaseValue: String): JsonData = JsonData(databaseValue)

  override fun encode(value: JsonData): String = value.raw
}
