package example.crud

import kotlin.Array
import kotlin.Int
import norm.ColumnAdapter

public class IntSetAdapter : ColumnAdapter<IntSet, Array<Int?>> {
  override fun decode(databaseValue: Array<Int?>): IntSet = IntSet(databaseValue.asList())

  override fun encode(`value`: IntSet): Array<Int?> = value.value.toTypedArray()
}
