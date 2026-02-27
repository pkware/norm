package com.example

import norm.ColumnAdapter

class CustomMoodAdapter : ColumnAdapter<CustomMood, String> {
  override fun decode(databaseValue: String): CustomMood = when (databaseValue) {
    "happy" -> CustomMood.HAPPY
    "sad" -> CustomMood.SAD
    "angry" -> CustomMood.ANGRY
    else -> error("Unknown mood: $databaseValue")
  }

  override fun encode(value: CustomMood): String = when (value) {
    CustomMood.HAPPY -> "happy"
    CustomMood.SAD -> "sad"
    CustomMood.ANGRY -> "angry"
  }
}
