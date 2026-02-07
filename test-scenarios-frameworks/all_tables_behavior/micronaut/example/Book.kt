package example

import io.micronaut.`data`.`annotation`.Id
import io.micronaut.`data`.`annotation`.MappedEntity
import io.micronaut.data.annotation.MappedProperty
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
@MappedEntity("book")
public data class Book(
  @field:Id
  public val id: Int,
  public val title: String,
  @field:MappedProperty("author_id")
  public val authorId: Int,
)
