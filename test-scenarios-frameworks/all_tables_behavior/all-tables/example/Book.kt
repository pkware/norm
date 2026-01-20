package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class Book(
  public val id: Int,
  public val title: String,
  public val author_id: Int,
)
