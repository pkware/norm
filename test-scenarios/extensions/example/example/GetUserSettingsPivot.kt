package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetUserSettingsPivot(
  public val user_id: Int?,
  public val setting1: String?,
  public val setting2: String?,
)
