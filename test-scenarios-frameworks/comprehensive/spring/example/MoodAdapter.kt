package example

import java.lang.IllegalArgumentException
import kotlin.String
import norm.ColumnAdapter
import org.springframework.stereotype.Component

@Component
public class MoodAdapter : ColumnAdapter<Mood, String> {
  override fun decode(databaseValue: String): Mood = when (databaseValue) {
    "happy" -> Mood.HAPPY
    "sad" -> Mood.SAD
    "angry" -> Mood.ANGRY
    else -> throw IllegalArgumentException("Unknown Mood database value: " + databaseValue)
  }

  override fun encode(`value`: Mood): String = value.databaseValue
}
