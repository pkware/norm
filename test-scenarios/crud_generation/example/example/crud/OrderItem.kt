package example.crud

import java.math.BigDecimal
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `order_item` table.
 */
@JvmRecord
public data class OrderItem(
  public val order_id: Int,
  public val item_id: Int,
  public val quantity: Int,
  public val price: BigDecimal,
)
