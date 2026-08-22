package example

import java.sql.Blob
import norm.ColumnAdapter

public class LargeObjectRefAdapter : ColumnAdapter<LargeObjectRef, Blob> {
  override fun decode(databaseValue: Blob): LargeObjectRef = LargeObjectRef(databaseValue)

  override fun encode(`value`: LargeObjectRef): Blob = value.value
}
