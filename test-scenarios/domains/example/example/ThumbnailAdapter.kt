package example

import kotlin.ByteArray
import norm.ColumnAdapter

public class ThumbnailAdapter : ColumnAdapter<Thumbnail, ByteArray> {
  override fun decode(databaseValue: ByteArray): Thumbnail = Thumbnail(databaseValue)

  override fun encode(`value`: Thumbnail): ByteArray = value.value
}
