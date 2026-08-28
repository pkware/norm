package example.crud

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * Maps to the `quoted_columns` table.
 */
@JvmRecord
public data class QuotedColumns(
  public val id: Int,
  public val Foo: String,
  public val `My Col`: String?,
)
