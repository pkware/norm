package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT setting_key, setting_value
 * FROM user_settings
 * WHERE user_id = ?
 * ```
 *
 * @property setting_key (`user_settings.setting_key`)
 * @property setting_value (`user_settings.setting_value`)
 */
@JvmRecord
public data class GetUserSettings(
  public val setting_key: String,
  public val setting_value: String?,
)
