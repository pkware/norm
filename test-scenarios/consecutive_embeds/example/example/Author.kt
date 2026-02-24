package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
public data class Author(
  public val id: Int,
  public val name: String,
)
