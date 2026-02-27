package com.example

import norm.ColumnAdapter

class UserPreferencesAdapter : ColumnAdapter<UserPreferences, String> {
  override fun decode(databaseValue: String): UserPreferences =
    UserPreferences(databaseValue)

  override fun encode(value: UserPreferences): String =
    value.raw
}
