package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT user_id, setting1, setting2
 * FROM crosstab(?) AS ct(user_id int, setting1 text, setting2 text)
 * ```
 */
@JvmRecord
public data class GetUserSettingsPivot(
  public val user_id: Int?,
  public val setting1: String?,
  public val setting2: String?,
)
