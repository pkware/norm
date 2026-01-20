package example

import io.micronaut.`data`.`annotation`.Id
import io.micronaut.`data`.`annotation`.MappedEntity
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
@MappedEntity("publisher")
public data class Publisher(
  @field:Id
  public val id: Int,
  public val company_name: String,
)
