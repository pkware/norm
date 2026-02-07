package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord
import org.springframework.`data`.`annotation`.Id
import org.springframework.`data`.relational.core.mapping.Column
import org.springframework.`data`.relational.core.mapping.Table

@JvmRecord
@Table("publisher")
public data class Publisher(
  @Id
  public val id: Int,
  @Column("company_name")
  public val companyName: String,
)
