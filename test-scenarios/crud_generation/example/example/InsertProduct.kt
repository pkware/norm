package example

import java.math.BigDecimal
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * INSERT INTO product (name, price, tax) VALUES (?, ?, ?) RETURNING id, total
 * ```
 *
 * @property id (`product.id`)
 * @property total (`product.total`)
 */
@JvmRecord
public data class InsertProduct(
  public val id: Int,
  public val total: BigDecimal?,
)
