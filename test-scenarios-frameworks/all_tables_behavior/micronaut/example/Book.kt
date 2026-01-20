package example

import io.micronaut.`data`.`annotation`.Id
import io.micronaut.`data`.`annotation`.MappedEntity
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
@MappedEntity("book")
public data class Book(
  @field:Id
  public val id: Int,
  public val title: String,
  public val author_id: Int,
)
