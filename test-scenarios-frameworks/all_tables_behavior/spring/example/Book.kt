package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord
import org.springframework.`data`.`annotation`.Id
import org.springframework.`data`.relational.core.mapping.Table

@JvmRecord
@Table("book")
public data class Book(
  @Id
  public val id: Int,
  public val title: String,
  public val author_id: Int,
)
