package com.example

import norm.ColumnAdapter

class JsonDataAdapter : ColumnAdapter<JsonData, String> {
  override fun decode(databaseValue: String): JsonData = JsonData(databaseValue)

  override fun encode(value: JsonData): String = value.raw
}
