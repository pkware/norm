package example

import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.Transacter

public interface Queries : Transacter {
  /**
   * Two consecutive embeds
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  public fun <T : Any> getTwoConsecutiveEmbeds(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
  ) -> T): T

  /**
   * Two consecutive embeds
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  public fun getTwoConsecutiveEmbeds(id: Int): GetTwoConsecutiveEmbeds = getTwoConsecutiveEmbeds(id, ::GetTwoConsecutiveEmbeds)

  /**
   * Three consecutive embeds - tests cumulative offset errors
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   * 6-7: reviewer (id, reviewer_name)
   * BUG HYPOTHESIS: Second and third embeds may start at wrong indices
   */
  @Throws(SQLException::class)
  public fun <T : Any> getThreeConsecutiveEmbeds(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
    reviewer_id: Int,
    reviewer_reviewer_name: String,
  ) -> T): T

  /**
   * Three consecutive embeds - tests cumulative offset errors
   * Expected indices:
   * 1-2: author (id, name)
   * 3-5: publisher (id, company_name, country)
   * 6-7: reviewer (id, reviewer_name)
   * BUG HYPOTHESIS: Second and third embeds may start at wrong indices
   */
  @Throws(SQLException::class)
  public fun getThreeConsecutiveEmbeds(id: Int): GetThreeConsecutiveEmbeds = getThreeConsecutiveEmbeds(id, ::GetThreeConsecutiveEmbeds)

  /**
   * Embed, regular, embed pattern
   * Expected indices:
   * 1-2: author (id, name)
   * 3: b.title
   * 4-6: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  public fun <T : Any> getEmbedRegularEmbed(id: Int, mapper: (
    author_id: Int,
    author_name: String,
    title: String,
    publisher_id: Int,
    publisher_company_name: String,
    publisher_country: String,
  ) -> T): T

  /**
   * Embed, regular, embed pattern
   * Expected indices:
   * 1-2: author (id, name)
   * 3: b.title
   * 4-6: publisher (id, company_name, country)
   */
  @Throws(SQLException::class)
  public fun getEmbedRegularEmbed(id: Int): GetEmbedRegularEmbed = getEmbedRegularEmbed(id, ::GetEmbedRegularEmbed)
}
