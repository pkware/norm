package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.Transacter

public interface Queries : Transacter {
  @Throws(SQLException::class)
  public fun <T : Any> getAuthor(id: Int, mapper: (
    id: Int,
    name: String,
    email: String?,
  ) -> T): T

  @Throws(SQLException::class)
  public fun getAuthor(id: Int): Author = getAuthor(id, ::Author)

  @Throws(SQLException::class)
  public fun <T : Any> getBook(id: Int, mapper: (
    id: Int,
    title: String,
    authorId: Int,
  ) -> T): T

  @Throws(SQLException::class)
  public fun getBook(id: Int): Book = getBook(id, ::Book)
}
