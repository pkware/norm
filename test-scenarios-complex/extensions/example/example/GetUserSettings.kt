package example

import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class GetUserSettings(
  public val setting_key: String,
  public val setting_value: String?,
)
