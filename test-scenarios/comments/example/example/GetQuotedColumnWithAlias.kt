package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT id, "Foo" AS bar FROM tq WHERE id = ?
 * ```
 *
 * @property id (`tq.id`)
 * @property bar Mixed-case column, only referenceable quoted. (`tq."Foo"`)
 */
@JvmRecord
public data class GetQuotedColumnWithAlias(
  public val id: Int,
  public val bar: String,
)
