package example

import java.math.BigDecimal
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `product` table.
 */
@JvmRecord
public data class Product(
  public val id: Int,
  public val name: String,
  public val price: BigDecimal,
  public val tax: BigDecimal,
  public val total: BigDecimal?,
)
