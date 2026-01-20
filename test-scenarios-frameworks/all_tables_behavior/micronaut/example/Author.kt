package example

import io.micronaut.`data`.`annotation`.Id
import io.micronaut.`data`.`annotation`.MappedEntity
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
@MappedEntity("author")
public data class Author(
  @field:Id
  public val id: Int,
  public val name: String,
  public val email: String?,
)
