package norm.e2e

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.example.CalendarDate
import com.example.CalendarDateAdapter
import com.example.CustomMood
import com.example.CustomMoodAdapter
import com.example.JsonData
import com.example.JsonDataAdapter
import com.example.UserPreferences
import com.example.UserPreferencesAdapter
import example.typemappings.EventDate
import example.typemappings.JsonDocument
import example.typemappings.NoteText
import example.typemappings.PositiveInteger
import example.typemappings.PostgresQueries
import example.typemappings.Queries
import example.typemappings.Schedules
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/**
 * E2E tests for the type_mappings test scenario.
 *
 * Validates that Norm's column adapter injection works correctly end-to-end:
 * - Type-level overrides: `mood` → [CustomMood], `jsonb` → [JsonData]
 * - Column-level overrides: `users.preferences` → [UserPreferences] (takes precedence over
 *   the jsonb type-level mapping for that specific column)
 * - Auto-generated domain adapters still work alongside user-configured ones
 * - Array columns of domains (`event_date[]`, `positive_integer[]`, `note_text[]`) and of a type with a
 *   type-level override (`date[]` → [CalendarDate]).
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
      dateAdapter = CalendarDateAdapter(),
      jsonAdapter = JsonDataAdapter(),
      jsonbAdapter = JsonDataAdapter(),
      moodAdapter = CustomMoodAdapter(),
      usersPreferencesAdapter = UserPreferencesAdapter(),
    )
  }

  override fun schemaFile(): File = projectRoot.resolve("test-scenarios/type_mappings/schema.sql")

  @Test
  fun `type-level mood mapping decodes enum to idiomatic Kotlin constants`() {
    insertUser(mood = "happy")

    val user = queries.getUserById(1) { _, _, _, mood, metadata, preferences, _, _ ->
      Triple(mood, metadata, preferences)
    }

    // Postgres "happy" → CustomMood.HAPPY (SCREAMING_SNAKE_CASE), not the raw String
    assertThat(user.first).isEqualTo(CustomMood.HAPPY)
  }

  @Test
  fun `type-level jsonb mapping decodes to JsonData for metadata column`() {
    insertUser(metadata = """{"key": "value"}""")

    val user = queries.getUserById(1) { _, _, _, _, metadata, _, _, _ -> metadata }

    assertThat(user).isEqualTo(JsonData("""{"key": "value"}"""))
  }

  @Test
  fun `column-level override takes precedence over type-level jsonb mapping for preferences`() {
    insertUser(preferences = """{"theme": "dark"}""")

    val user = queries.getUserById(1) { _, _, _, _, _, preferences, _, _ -> preferences }

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
    val age = queries.getUserById(1) { _, _, age, _, _, _, _, _ -> listOf(age) }.first()

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

    val (mood, metadata, preferences) = queries.getUserById(1) { _, _, _, mood, metadata, preferences, _, _ ->
      Triple(mood, metadata, preferences)
    }

    assertThat(mood).isEqualTo(CustomMood.SAD)
    assertThat(metadata).isEqualTo(JsonData("""{"inserted": true}"""))
    assertThat(preferences).isEqualTo(UserPreferences("""{"lang": "en"}"""))
  }

  @Nested
  inner class JsonWireType {

    @Test
    fun `type-level json mapping round-trips through a json column`() {
      queries.createDocument(payload = JsonData("""{"kind":"plain"}"""), doc = null)

      val payload = queries.getDocumentById(1) { _, payload, _ -> payload }

      // json stores the text verbatim, so the value comes back exactly as written. A setString
      // binding would have failed the insert outright with a type mismatch.
      assertThat(payload).isEqualTo(JsonData("""{"kind":"plain"}"""))
    }

    @Test
    fun `domain over json round-trips through its auto-generated adapter`() {
      queries.createDocument(
        payload = JsonData("{}"),
        doc = JsonDocument("""{"kind":"domain"}"""),
      )

      val doc = queries.getDocumentById(1) { _, _, doc -> listOf(doc) }.first()

      assertThat(doc).isEqualTo(JsonDocument("""{"kind":"domain"}"""))
    }

    @Test
    fun `null domain over json binds as SQL NULL`() {
      queries.createDocument(payload = JsonData("{}"), doc = null)

      val doc = queries.getDocumentById(1) { _, _, doc -> listOf(doc) }.first()

      assertThat(doc).isNull()
    }
  }

  @Nested
  inner class ArraysOfAdaptedTypes {

    @Test
    fun `write and read back enum array via updatePastMoods`() {
      insertUser()
      queries.updatePastMoods(
        past_moods = arrayOf(CustomMood.HAPPY, CustomMood.SAD),
        tag_list = null,
        id = 1,
      )

      val pastMoods = readPastMoods()

      assertThat(pastMoods).isNotNull()
      assertThat(pastMoods!!.toList()).containsExactly(CustomMood.HAPPY, CustomMood.SAD)
    }

    @Test
    fun `write and read back jsonb array via updatePastMoods`() {
      insertUser()
      queries.updatePastMoods(
        past_moods = null,
        tag_list = arrayOf(JsonData("""{"a":1}"""), JsonData("""{"b":2}""")),
        id = 1,
      )

      val tagList = readTagList()

      assertThat(tagList).isNotNull()
      // Postgres normalizes jsonb whitespace: {"a":1} → {"a": 1}
      assertThat(tagList!!.toList()).containsExactly(JsonData("""{"a": 1}"""), JsonData("""{"b": 2}"""))
    }

    @Test
    fun `null array column returns null`() {
      insertUser()

      val pastMoods = readPastMoods()

      assertThat(pastMoods).isNull()
    }

    @Test
    fun `array with null elements preserves nulls`() {
      insertUser()
      queries.updatePastMoods(
        past_moods = arrayOf(CustomMood.HAPPY, null, CustomMood.ANGRY),
        tag_list = null,
        id = 1,
      )

      val pastMoods = readPastMoods()

      assertThat(pastMoods).isNotNull()
      assertThat(pastMoods!!.toList()).containsExactly(CustomMood.HAPPY, null, CustomMood.ANGRY)
    }

    @Test
    fun `empty array round-trips correctly`() {
      insertUser()
      queries.updatePastMoods(
        past_moods = emptyArray(),
        tag_list = emptyArray(),
        id = 1,
      )

      val pastMoods = readPastMoods()

      assertThat(pastMoods).isNotNull()
      assertThat(pastMoods!!.toList()).containsExactly()
    }

    /** Reads past_moods for user 1, boxing the nullable array in a list to satisfy `T : Any`. */
    private fun readPastMoods(): Array<CustomMood?>? =
      queries.getUserById(1) { _, _, _, _, _, _, past_moods, _ -> listOf(past_moods) }.first()

    /** Reads tag_list for user 1, boxing the nullable array in a list to satisfy `T : Any`. */
    private fun readTagList(): Array<JsonData?>? =
      queries.getUserById(1) { _, _, _, _, _, _, _, tag_list -> listOf(tag_list) }.first()
  }

  @Nested
  inner class ArraysOfDomainsAndBaseTypeOverrides {

    private val firstDate = LocalDate.of(2024, 2, 29)
    private val secondDate = LocalDate.of(1999, 12, 31)
    private val trickyText = """a"b\c,d{e}"""

    @Test
    fun `event_date domain array round-trips through updateSchedule`() {
      insertSchedule()
      updateSchedule(eventDates = arrayOf(EventDate(firstDate), EventDate(secondDate)))

      assertThat(readSchedule().event_dates!!.toList()).containsExactly(EventDate(firstDate), EventDate(secondDate))
    }

    @Test
    fun `event_date domain array reads a raw SQL literal`() {
      insertSchedule("event_dates", "'{2024-02-29,1999-12-31}'")

      assertThat(readSchedule().event_dates!!.toList()).containsExactly(EventDate(firstDate), EventDate(secondDate))
    }

    @Test
    fun `event_date domain array preserves a NULL element`() {
      insertSchedule()
      updateSchedule(eventDates = arrayOf(EventDate(firstDate), null))

      assertThat(readSchedule().event_dates!!.toList()).containsExactly(EventDate(firstDate), null)
    }

    @Test
    fun `positive_integer domain array round-trips through updateSchedule`() {
      insertSchedule()
      updateSchedule(scores = arrayOf(PositiveInteger(1), PositiveInteger(2_000_000_000)))

      assertThat(readSchedule().scores!!.toList()).containsExactly(PositiveInteger(1), PositiveInteger(2_000_000_000))
    }

    @Test
    fun `positive_integer domain array reads a raw SQL literal`() {
      insertSchedule("scores", "'{7,8}'")

      assertThat(readSchedule().scores!!.toList()).containsExactly(PositiveInteger(7), PositiveInteger(8))
    }

    @Test
    fun `positive_integer domain array preserves a NULL element`() {
      insertSchedule()
      updateSchedule(scores = arrayOf(null, PositiveInteger(3)))

      assertThat(readSchedule().scores!!.toList()).containsExactly(null, PositiveInteger(3))
    }

    @Test
    fun `note_text domain array round-trips through updateSchedule`() {
      insertSchedule()
      updateSchedule(notes = arrayOf(NoteText("first"), NoteText("second")))

      assertThat(readSchedule().notes!!.toList()).containsExactly(NoteText("first"), NoteText("second"))
    }

    @Test
    fun `note_text domain array reads a raw SQL literal`() {
      insertSchedule("notes", "'{alpha,beta}'")

      assertThat(readSchedule().notes!!.toList()).containsExactly(NoteText("alpha"), NoteText("beta"))
    }

    @Test
    fun `note_text domain array preserves a NULL element`() {
      insertSchedule()
      updateSchedule(notes = arrayOf(NoteText("first"), null))

      assertThat(readSchedule().notes!!.toList()).containsExactly(NoteText("first"), null)
    }

    @Test
    fun `note_text domain array round-trips an element with quote, backslash, comma and brace`() {
      insertSchedule()
      updateSchedule(notes = arrayOf(NoteText(trickyText), NoteText("plain")))

      assertThat(readSchedule().notes!!.toList()).containsExactly(NoteText(trickyText), NoteText("plain"))
    }

    @Test
    fun `note_text domain array reads a raw SQL literal containing quote, backslash, comma and brace`() {
      insertSchedule("notes", """'{"a\"b\\c,d{e}",plain}'""")

      assertThat(readSchedule().notes!!.toList()).containsExactly(NoteText(trickyText), NoteText("plain"))
    }

    @Test
    fun `date array with a type override round-trips through updateSchedule`() {
      insertSchedule()
      updateSchedule(holidays = arrayOf(CalendarDate(firstDate), CalendarDate(secondDate)))

      assertThat(readSchedule().holidays!!.toList()).containsExactly(CalendarDate(firstDate), CalendarDate(secondDate))
    }

    @Test
    fun `date array with a type override reads a raw SQL literal`() {
      insertSchedule("holidays", "'{2024-02-29,1999-12-31}'")

      assertThat(readSchedule().holidays!!.toList()).containsExactly(CalendarDate(firstDate), CalendarDate(secondDate))
    }

    @Test
    fun `date array with a type override preserves a NULL element`() {
      insertSchedule()
      updateSchedule(holidays = arrayOf(null, CalendarDate(secondDate)))

      assertThat(readSchedule().holidays!!.toList()).containsExactly(null, CalendarDate(secondDate))
    }

    @Test
    fun `null array columns read back as null`() {
      insertSchedule()

      val schedule = readSchedule()

      assertThat(schedule.event_dates).isNull()
      assertThat(schedule.scores).isNull()
      assertThat(schedule.notes).isNull()
      assertThat(schedule.holidays).isNull()
    }

    @Test
    fun `empty arrays round-trip for every column`() {
      insertSchedule()
      updateSchedule(
        eventDates = emptyArray(),
        scores = emptyArray(),
        notes = emptyArray(),
        holidays = emptyArray(),
      )

      val schedule = readSchedule()

      assertThat(schedule.event_dates!!.toList()).containsExactly()
      assertThat(schedule.scores!!.toList()).containsExactly()
      assertThat(schedule.notes!!.toList()).containsExactly()
      assertThat(schedule.holidays!!.toList()).containsExactly()
    }

    @Test
    fun `domain array columns read through a query with a parameter named statement`() {
      insertSchedule()
      updateSchedule(
        eventDates = arrayOf(EventDate(firstDate), null),
        scores = arrayOf(PositiveInteger(5)),
        notes = arrayOf(NoteText("note")),
        holidays = arrayOf(CalendarDate(secondDate)),
      )

      val columns = queries.getScheduleArraysByStatement(statement = 1) { eventDates, scores, notes, holidays ->
        listOf(eventDates?.toList(), scores?.toList(), notes?.toList(), holidays?.toList())
      }

      assertThat(columns).containsExactly(
        listOf(EventDate(firstDate), null),
        listOf(PositiveInteger(5)),
        listOf(NoteText("note")),
        listOf(CalendarDate(secondDate)),
      )
    }

    private fun insertSchedule(column: String? = null, literal: String? = null) {
      if (column == null) {
        executeRawSql("INSERT INTO schedules DEFAULT VALUES")
      } else {
        executeRawSql("INSERT INTO schedules ($column) VALUES ($literal)")
      }
    }

    private fun updateSchedule(
      eventDates: Array<EventDate?>? = null,
      scores: Array<PositiveInteger?>? = null,
      notes: Array<NoteText?>? = null,
      holidays: Array<CalendarDate?>? = null,
    ) {
      queries.updateSchedule(
        event_dates = eventDates,
        scores = scores,
        notes = notes,
        holidays = holidays,
        id = 1,
      )
    }

    private fun readSchedule(): Schedules = queries.getScheduleById(1)
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
