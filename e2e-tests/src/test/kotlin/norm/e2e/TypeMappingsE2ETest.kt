package norm.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.example.CustomMood
import com.example.CustomMoodAdapter
import com.example.JsonData
import com.example.JsonDataAdapter
import com.example.UserPreferences
import com.example.UserPreferencesAdapter
import example.typemappings.PostgresQueries
import example.typemappings.Queries
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection

/**
 * E2E tests for the type_mappings test scenario.
 *
 * Validates that Norm's column adapter injection works correctly end-to-end:
 * - Type-level overrides: `mood` → [CustomMood], `jsonb` → [JsonData]
 * - Column-level overrides: `users.preferences` → [UserPreferences] (takes precedence over
 *   the jsonb type-level mapping for that specific column)
 * - Auto-generated domain adapters still work alongside user-configured ones
 *
 * The generated [PostgresQueries] constructor requires user adapters without defaults; they
 * are provided here directly rather than through a DI container.
 */
class TypeMappingsE2ETest : PostgresTestBase() {

  private lateinit var queries: Queries

  @BeforeEach
  fun setupQueries() {
    queries = PostgresQueries(
      connectionProvider,
      jsonbAdapter = JsonDataAdapter(),
      moodAdapter = CustomMoodAdapter(),
      usersPreferencesAdapter = UserPreferencesAdapter(),
    )
  }

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/type_mappings/schema.sql")

  override fun cleanDatabase(connection: Connection) {
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        DROP TABLE IF EXISTS users CASCADE;
        DROP TYPE IF EXISTS mood;
        DROP DOMAIN IF EXISTS positive_integer;
        DROP DOMAIN IF EXISTS email;
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `type-level mood mapping decodes enum to idiomatic Kotlin constants`() {
    insertUser(mood = "happy")

    val user = queries.getUserById(1) { id, email, age, mood, metadata, preferences ->
      Triple(mood, metadata, preferences)
    }

    // Postgres "happy" → CustomMood.HAPPY (SCREAMING_SNAKE_CASE), not the raw String
    assertThat(user.first).isEqualTo(CustomMood.HAPPY)
  }

  @Test
  fun `type-level jsonb mapping decodes to JsonData for metadata column`() {
    insertUser(metadata = """{"key": "value"}""")

    val user = queries.getUserById(1) { _, _, _, _, metadata, _ -> metadata }

    assertThat(user).isEqualTo(JsonData("""{"key": "value"}"""))
  }

  @Test
  fun `column-level override takes precedence over type-level jsonb mapping for preferences`() {
    insertUser(preferences = """{"theme": "dark"}""")

    val user = queries.getUserById(1) { _, _, _, _, _, preferences -> preferences }

    // users.preferences → UserPreferences (column override), not JsonData (type override)
    assertThat(user).isEqualTo(UserPreferences("""{"theme": "dark"}"""))
  }

  @Test
  fun `nullable domain column returns null when value is absent`() {
    // Insert without age (nullable positive_integer domain)
    executeRawSql(
      """
      INSERT INTO users (email, current_mood, metadata, preferences)
      VALUES ('test@example.com', 'happy', '{}', '{}')
      """.trimIndent(),
    )

    // Box in a list to satisfy T : Any — the mapper cannot return a nullable T directly.
    val age = queries.getUserById(1) { _, _, age, _, _, _ -> listOf(age) }.first()

    assertThat(age).isNull()
  }

  @Test
  fun `insert round-trips all type-mapped values correctly`() {
    queries.createUser(
      email = example.typemappings.Email("insert@example.com"),
      age = null,
      current_mood = CustomMood.SAD,
      metadata = JsonData("""{"inserted": true}"""),
      preferences = UserPreferences("""{"lang": "en"}"""),
    )

    val (mood, metadata, preferences) = queries.getUserById(1) { _, _, _, mood, metadata, preferences ->
      Triple(mood, metadata, preferences)
    }

    assertThat(mood).isEqualTo(CustomMood.SAD)
    assertThat(metadata).isEqualTo(JsonData("""{"inserted": true}"""))
    assertThat(preferences).isEqualTo(UserPreferences("""{"lang": "en"}"""))
  }

  private fun insertUser(
    email: String = "user@example.com",
    mood: String = "happy",
    metadata: String = "{}",
    preferences: String = "{}",
  ) {
    executeRawSql(
      """
      INSERT INTO users (email, current_mood, metadata, preferences)
      VALUES ('$email', '$mood', '$metadata'::jsonb, '$preferences'::jsonb)
      """.trimIndent(),
    )
  }
}
