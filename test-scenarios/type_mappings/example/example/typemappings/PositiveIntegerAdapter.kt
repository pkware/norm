package example.typemappings

import kotlin.Int
import norm.ColumnAdapter

public class PositiveIntegerAdapter : ColumnAdapter<PositiveInteger, Int> {
  override fun decode(databaseValue: Int): PositiveInteger = PositiveInteger(databaseValue)

  override fun encode(`value`: PositiveInteger): Int = value.value
}
