package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `order` table.
 *
 * @property id Contains a literal \` backtick.
 */
@JvmRecord
public data class Order(
  public val id: Int,
  public val user: String,
)
