package example

import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord
import org.springframework.`data`.`annotation`.Id
import org.springframework.`data`.relational.core.mapping.Column
import org.springframework.`data`.relational.core.mapping.Table

/**
 * Maps to the `book` table.
 */
@JvmRecord
@Table("book")
public data class Book(
  @Id
  public val id: Int,
  public val title: String,
  @Column("author_id")
  public val authorId: Int,
)
