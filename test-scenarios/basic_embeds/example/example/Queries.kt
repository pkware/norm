package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.Many
import norm.Query

public interface Queries {
  /**
   * Simple embed: single table returned as embedded object
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthor(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): T

  /**
   * Simple embed: single table returned as embedded object
   */
  @Throws(SQLException::class)
  public fun getAuthor(id: Int): GetAuthor = getAuthor(id, ::GetAuthor)

  /**
   * Regular column before embed
   */
  public fun <T : Any> listBooksWithAuthors(mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): Many<T>

  /**
   * Regular column before embed
   */
  public fun listBooksWithAuthors(): Many<ListBooksWithAuthors> = listBooksWithAuthors(::ListBooksWithAuthors)

  public fun <T : Any> listBooksWithAuthorsDynamically(mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    author_email: String,
  ) -> T): Query<T>

  public fun listBooksWithAuthorsDynamically(): Query<ListBooksWithAuthors> = listBooksWithAuthorsDynamically(::ListBooksWithAuthors)

  /**
   * Regular column after embed
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAuthorWithBookTitle(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    author_email: String,
    title: String,
  ) -> T): T

  /**
   * Regular column after embed
   */
  @Throws(SQLException::class)
  public fun getAuthorWithBookTitle(id: Int): GetAuthorWithBookTitle = getAuthorWithBookTitle(id, ::GetAuthorWithBookTitle)
}
