package com.example

import jakarta.inject.Singleton
import norm.ColumnAdapter

/** Maps `jsonb` columns to [JsonData]. Registered as a bean so the generated `PostgresQueries` can require it. */
@Singleton
class JsonDataAdapter : ColumnAdapter<JsonData, String> {
  override fun decode(databaseValue: String): JsonData = JsonData(databaseValue)

  override fun encode(value: JsonData): String = value.raw
}
