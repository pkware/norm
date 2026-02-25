package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws

public interface Queries {
  /**
   * THE CRITICAL TEST: regular, embed, regular pattern
   * Expected indices:
   * 1: b.id
   * 2: b.title
   * 3-4: author (id, name)
   * 5: b.isbn
   * 6: b.published_year
   * BUG HYPOTHESIS: After the embed, isbn and published_year may use wrong indices
   */
  @Throws(SQLException::class)
  public fun <T : Any> getComplexBook(id: Int, mapper: (
    id: Int,
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    published_year: Int,
  ) -> T): T

  /**
   * THE CRITICAL TEST: regular, embed, regular pattern
   * Expected indices:
   * 1: b.id
   * 2: b.title
   * 3-4: author (id, name)
   * 5: b.isbn
   * 6: b.published_year
   * BUG HYPOTHESIS: After the embed, isbn and published_year may use wrong indices
   */
  @Throws(SQLException::class)
  public fun getComplexBook(id: Int): GetComplexBook = getComplexBook(id, ::GetComplexBook)

  /**
   * Sandwich pattern: regular columns on both sides of 3-column embed
   * Expected indices:
   * 1: b.title
   * 2: b.isbn
   * 3-5: publisher (id, company_name, country)
   * 6: b.page_count
   * 7: b.published_year
   */
  @Throws(SQLException::class)
  public fun <T : Any> getSandwichBook(id: Int, mapper: (
    title: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    page_count: Int,
    published_year: Int,
  ) -> T): T

  /**
   * Sandwich pattern: regular columns on both sides of 3-column embed
   * Expected indices:
   * 1: b.title
   * 2: b.isbn
   * 3-5: publisher (id, company_name, country)
   * 6: b.page_count
   * 7: b.published_year
   */
  @Throws(SQLException::class)
  public fun getSandwichBook(id: Int): GetSandwichBook = getSandwichBook(id, ::GetSandwichBook)

  /**
   * Multiple embeds with regular columns between
   * Expected indices:
   * 1: b.title
   * 2-3: author (id, name)
   * 4: b.isbn
   * 5-7: publisher (id, company_name, country)
   * 8: b.published_year
   */
  @Throws(SQLException::class)
  public fun <T : Any> getAlternatingBook(id: Int, mapper: (
    title: String,
    author_id: Int,
    author_name: String,
    isbn: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    published_year: Int,
  ) -> T): T

  /**
   * Multiple embeds with regular columns between
   * Expected indices:
   * 1: b.title
   * 2-3: author (id, name)
   * 4: b.isbn
   * 5-7: publisher (id, company_name, country)
   * 8: b.published_year
   */
  @Throws(SQLException::class)
  public fun getAlternatingBook(id: Int): GetAlternatingBook = getAlternatingBook(id, ::GetAlternatingBook)
}
