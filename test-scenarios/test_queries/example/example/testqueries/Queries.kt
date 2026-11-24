package example.testqueries

import kotlin.Any
import kotlin.Int
import kotlin.String
import norm.Many
import norm.Query
import norm.Transactable

public interface Queries : Transactable {
  /**
   * ```sql
   * SELECT * FROM author ORDER BY id
   * ```
   */
  public fun <T : Any> listAuthors(mapper: (id: Int, name: String) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM author ORDER BY id
   * ```
   */
  public fun listAuthors(): Many<Author> = listAuthors(::Author)

  public fun <T : Any> listAuthorsDynamically(mapper: (id: Int, name: String) -> T): Query<T>

  public fun listAuthorsDynamically(): Query<Author> = listAuthorsDynamically(::Author)
}
