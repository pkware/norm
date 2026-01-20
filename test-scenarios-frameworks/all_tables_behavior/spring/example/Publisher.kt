package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord
import org.springframework.`data`.`annotation`.Id
import org.springframework.`data`.relational.core.mapping.Table

@JvmRecord
@Table("publisher")
public data class Publisher(
  @Id
  public val id: Int,
  public val company_name: String,
)
