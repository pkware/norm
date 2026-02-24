package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetUserSettingsByCategory(
  public val row_name: String?,
  public val category1: Int?,
  public val category2: Int?,
  public val category3: Int?,
)
