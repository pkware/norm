package example

import kotlin.Array
import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `users` table.
 */
@JvmRecord
public data class Users(
  public val id: Int,
  public val email: Email,
  public val age: PositiveInteger?,
  public val zip_code: UsPostalCode?,
  public val current_mood: Mood,
  public val previous_mood: Mood?,
  public val past_moods: Array<Mood?>?,
  public val scores: Array<PositiveInteger?>?,
  public val placed_at: OrderPlacedAt,
  public val external_ref: ExternalReference?,
  public val preferred_date: PreferredDate?,
  public val opening_time: OpeningTime?,
  public val meeting_time_tz: MeetingTimeTz?,
  public val created_at_local: CreatedAtLocal?,
  public val large_object_ref: LargeObjectRef?,
  public val thumbnail: Thumbnail?,
)
