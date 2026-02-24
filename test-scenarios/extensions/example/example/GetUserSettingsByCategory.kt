package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT row_name, category1, category2, category3
 * FROM crosstab(?, ?) AS ct(row_name text, category1 int, category2 int, category3 int)
 * ```
 */
@JvmRecord
public data class GetUserSettingsByCategory(
  public val row_name: String?,
  public val category1: Int?,
  public val category2: Int?,
  public val category3: Int?,
)
