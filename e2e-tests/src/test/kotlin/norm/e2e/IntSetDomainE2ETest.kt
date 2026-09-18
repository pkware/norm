package norm.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import example.crud.IntSet
import example.crud.PostgresQueries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end coverage for `CREATE DOMAIN int_set AS int[]` (a domain over an array type), against
 * the real `tag_group` table in `test-scenarios/crud_generation/schema.sql`.
 *
 * The value class generated for such a domain wraps `List<Int?>`, not `Array<Int?>` — see
 * [norm.generator.domainKotlinPropertyType]'s KDoc for why. [readingTheSameRowTwiceProducesEqualValues]
 * is the regression test for that decision: `kotlin.Array` has identity equality, so if the value
 * class wrapped `Array` instead, two separate JDBC reads of the same row would each build a distinct
 * array instance and this test would fail even though both reads returned the same database row.
 */
class IntSetDomainE2ETest : PostgresTestBase() {

  private lateinit var queries: PostgresQueries

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/crud_generation/schema.sql")

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(connectionProvider)
  }

  @Test
  fun `insert and read back a domain-over-array value, including a null element`() {
    val requiredTags = IntSet(listOf(1, null, 3))

    val id = queries.insertTagGroup(requiredTags, optional_tags = null)

    val stored = queries.findTagGroupById(id).list().single()
    assertThat(stored.required_tags).isEqualTo(requiredTags)
    assertThat(stored.optional_tags).isNull()
  }

  @Test
  fun `reading the same row twice produces equal values`() {
    val requiredTags = IntSet(listOf(1, null, 3))
    val optionalTags = IntSet(listOf(4, 5))

    val id = queries.insertTagGroup(requiredTags, optionalTags)

    val firstRead = queries.findTagGroupById(id).list().single()
    val secondRead = queries.findTagGroupById(id).list().single()

    assertThat(firstRead.required_tags).isEqualTo(secondRead.required_tags)
    assertThat(firstRead.optional_tags).isEqualTo(secondRead.optional_tags)
    assertThat(firstRead).isEqualTo(secondRead)
  }
}
