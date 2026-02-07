package example

import io.micronaut.`data`.`annotation`.Id
import io.micronaut.`data`.`annotation`.MappedEntity
import io.micronaut.`data`.`annotation`.MappedProperty
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

@JvmRecord
@MappedEntity("publisher")
public data class Publisher(
  @field:Id
  public val id: Int,
  @field:MappedProperty("company_name")
  public val companyName: String,
)
