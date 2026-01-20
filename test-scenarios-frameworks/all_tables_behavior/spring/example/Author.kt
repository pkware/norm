package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord
import org.springframework.`data`.`annotation`.Id
import org.springframework.`data`.relational.core.mapping.Table

@JvmRecord
@Table("author")
public data class Author(
  @Id
  public val id: Int,
  public val name: String,
  public val email: String?,
)
