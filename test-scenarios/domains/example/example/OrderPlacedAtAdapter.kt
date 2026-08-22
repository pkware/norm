package example

import java.time.Instant
import norm.ColumnAdapter

public class OrderPlacedAtAdapter : ColumnAdapter<OrderPlacedAt, Instant> {
  override fun decode(databaseValue: Instant): OrderPlacedAt = OrderPlacedAt(databaseValue)

  override fun encode(`value`: OrderPlacedAt): Instant = value.value
}
