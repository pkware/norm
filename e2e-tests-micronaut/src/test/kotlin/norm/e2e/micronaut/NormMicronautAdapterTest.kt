package norm.e2e.micronaut

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.example.JsonData
import example.EmailAddress
import example.Mood
import example.PostgresQueries
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Statement
import javax.sql.DataSource

/**
 * Integration tests proving that Norm's adapter injection works through Micronaut's DI.
 *
 * These tests verify:
 * - Auto-generated enum adapters ([Mood] → [example.MoodAdapter]) are injected via `@Singleton`
 * - Auto-generated domain adapters ([EmailAddress]) are injected via `@Singleton`
 * - User-configured adapters ([JsonData] → [com.example.JsonDataAdapter]) are injected as required beans
 * - Nullable adapted columns decode to `null` correctly
 * - Adapted types participate in `@Transactional` rollback
 */
@MicronautTest
class NormMicronautAdapterTest {

  @Inject
  lateinit var queries: PostgresQueries

  @Inject
  lateinit var personService: PersonService

  @Inject
  lateinit var dataSource: DataSource

  @BeforeEach
  fun cleanDatabase() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DELETE FROM person")
        statement.execute("DELETE FROM book")
        statement.execute("DELETE FROM author")
      }
    }
  }

  @Test
  fun `auto-generated enum adapter decodes through Micronaut DI`() {
    val id = insertPersonRaw(mood = "happy")

    val person = queries.getPersonById(id) { _, _, _, mood, _ -> mood }

    assertThat(person).isEqualTo(Mood.HAPPY)
  }

  @Test
  fun `auto-generated domain adapter decodes through Micronaut DI`() {
    val id = insertPersonRaw()

    val person = queries.getPersonById(id) { _, _, contactEmail, _, _ -> contactEmail }

    assertThat(person).isEqualTo(EmailAddress("person@example.com"))
  }

  @Test
  fun `user-configured jsonb adapter decodes through Micronaut DI`() {
    val id = insertPersonRaw(bio = """{"interests": ["coding"]}""")

    // Box in a list to satisfy T : Any — the mapper cannot return a nullable T directly.
    val bio = queries.getPersonById(id) { _, _, _, _, bio -> listOf(bio) }.first()

    assertThat(bio).isEqualTo(JsonData("""{"interests": ["coding"]}"""))
  }

  @Test
  fun `nullable adapted column returns null`() {
    // Insert with bio=null
    val id = insertPersonRaw()

    // Box in a list to satisfy T : Any — the mapper cannot return a nullable T directly.
    val bio = queries.getPersonById(id) { _, _, _, _, bio -> listOf(bio) }.first()

    assertThat(bio).isNull()
  }

  @Test
  fun `insert round-trip encodes and decodes all adapted types`() {
    queries.createPerson(
      name = "Test Person",
      contact_email = EmailAddress("roundtrip@example.com"),
      current_mood = Mood.SAD,
      bio = JsonData("""{"round": "trip"}"""),
    )

    // The id is always 1 here because cleanDatabase() deletes all rows and this is the first insert.
    // Postgres SERIAL sequences are not reset by DELETE, but since there is only one insert in this
    // test, we can query the single row.
    val person = dataSource.connection.use { connection ->
      connection.prepareStatement("SELECT id FROM person LIMIT 1").use { statement ->
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getInt(1)
        }
      }
    }.let { id ->
      queries.getPersonById(id) { _, name, contactEmail, mood, bio ->
        listOf(name, contactEmail, mood, bio)
      }
    }

    assertThat(person[0]).isEqualTo("Test Person")
    assertThat(person[1]).isEqualTo(EmailAddress("roundtrip@example.com"))
    assertThat(person[2]).isEqualTo(Mood.SAD)
    assertThat(person[3]).isEqualTo(JsonData("""{"round": "trip"}"""))
  }

  @Test
  fun `transaction rollback with adapted types`() {
    assertFailure {
      personService.createPersonThenFail(
        name = "Ghost",
        contactEmail = EmailAddress("ghost@example.com"),
        currentMood = Mood.ANGRY,
        bio = JsonData("""{"gone": true}"""),
      )
    }

    val count = dataSource.connection.use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM person").use { statement ->
        statement.executeQuery().use { resultSet ->
          resultSet.next()
          resultSet.getLong(1)
        }
      }
    }

    assertThat(count).isEqualTo(0L)
  }

  /**
   * Inserts a person using raw SQL and returns the generated ID.
   *
   * Uses raw JDBC (not Norm) so the test isolates Norm's decode path — it only needs to prove
   * that adapters decode database values correctly, not that they also encode correctly (the
   * round-trip test covers that).
   */
  private fun insertPersonRaw(
    name: String = "Test Person",
    email: String = "person@example.com",
    mood: String = "happy",
    bio: String? = null,
  ): Int = dataSource.connection.use { connection ->
    connection.prepareStatement(
      """
        INSERT INTO person (name, contact_email, current_mood, bio)
        VALUES (?, ?, ?::mood, ?::jsonb)
      """.trimIndent(),
      Statement.RETURN_GENERATED_KEYS,
    ).use { statement ->
      statement.setString(1, name)
      statement.setString(2, email)
      statement.setString(3, mood)
      statement.setString(4, bio)
      statement.executeUpdate()
      statement.generatedKeys.use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }
  }
}
