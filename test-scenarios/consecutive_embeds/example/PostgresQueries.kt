package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.NormDriver
import norm.RealTransacter

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  /**
   * Two consecutive embeds
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  override fun <T : Any> getTwoConsecutiveEmbeds(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
  ) -> T): T {
    val sql = """
        |SELECT
        |  author.id, author.name,
        |  publisher.id, publisher.company_name, publisher.country
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getString(4),
        getString(5),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  /**
   * Three consecutive embeds - tests cumulative offset errors
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   * 6-7: reviewer (id, reviewer_name)
   * BUG HYPOTHESIS: Second and third embeds may start at wrong indices
   */
  @Throws(SQLException::class)
  override fun <T : Any> getThreeConsecutiveEmbeds(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    reviewer_id: Int,
    reviewer_reviewer_name: String,
  ) -> T): T {
    val sql = """
        |SELECT
        |  author.id, author.name,
        |  publisher.id, publisher.company_name, publisher.country,
        |  reviewer.id, reviewer.reviewer_name
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |JOIN reviewer ON b.reviewer_id = reviewer.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
        getString(4),
        getString(5),
        getInt(6),
        getString(7),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  /**
   * Embed, regular, embed pattern
   * Expected indices:
   * 1-2: author (id, name)
   * 3: b.title
   * 4-6: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  override fun <T : Any> getEmbedRegularEmbed(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    title: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
  ) -> T): T {
    val sql = """
        |SELECT
        |  author.id, author.name,
        |  b.title,
        |  publisher.id, publisher.company_name, publisher.country
        |FROM book b
        |JOIN author ON b.author_id = author.id
        |JOIN publisher ON b.publisher_id = publisher.id
        |WHERE b.id = ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
        getInt(4),
        getString(5),
        getString(6),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
