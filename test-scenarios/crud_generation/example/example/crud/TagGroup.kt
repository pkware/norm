package example.crud

import kotlin.Int
import kotlin.jvm.JvmRecord

/**
 * Maps to the `tag_group` table.
 */
@JvmRecord
public data class TagGroup(
  public val id: Int,
  public val required_tags: IntSet,
  public val optional_tags: IntSet?,
)
