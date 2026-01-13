package norm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManyTest {

  @Mock
  lateinit var dataSource: DataSource

  @Mock
  lateinit var connection: Connection

  @Mock
  lateinit var preparedStatement: PreparedStatement

  @Mock
  lateinit var resultSet: ResultSet

  private lateinit var driver: NormDriver

  @BeforeEach
  fun setup() {
    whenever(dataSource.connection).thenReturn(connection)
    whenever(connection.autoCommit).thenReturn(true)
    whenever(connection.prepareStatement(any<String>())).thenReturn(preparedStatement)
    whenever(preparedStatement.executeQuery()).thenReturn(resultSet)
    driver = NormDriver(dataSource)
  }

  private fun many(): Many<String?> = driver.queryMany("SELECT name FROM users", { getString(1) })

  @Nested
  inner class Collection {
    @Test
    fun `ResultSet gets closed`() {
      whenever(resultSet.next()).thenReturn(false)

      many().list()

      verify(resultSet).close()
    }

    @Test
    fun `collection has all ResultSet rows`() {
      whenever(resultSet.next()).thenReturn(true, true, true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice", "Bob", "Charlie")

      val result = many().list()

      assertThat(result).containsExactly("Alice", "Bob", "Charlie")
    }
  }

  @Nested
  inner class FirstOrNull {
    @Test
    fun `ResultSet gets closed`() {
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      many().firstOrNull()

      verify(resultSet).close()
    }

    @Test
    fun `empty ResultSet returns null`() {
      whenever(resultSet.next()).thenReturn(false)

      val result = many().firstOrNull()

      assertThat(result).isNull()
    }

    @Test
    fun `first ResultSet value gets returned when null`() {
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn(null)

      val result = many().firstOrNull()

      assertThat(result).isNull()
    }

    @Test
    fun `first ResultSet value gets returned when not null`() {
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      val result = many().firstOrNull()

      assertThat(result).isEqualTo("Alice")
    }
  }

  @Nested
  inner class Stream {
    @Test
    fun `ResultSet gets closed during forEachRemaining`() {
      whenever(resultSet.next()).thenReturn(true, true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice", "Bob")

      // forEach uses forEachRemaining internally
      many().stream().forEach { }

      verify(resultSet).close()
    }

    @Test
    fun `ResultSet gets closed after last tryAdvance`() {
      whenever(resultSet.next()).thenReturn(true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice")

      val iterator = many().stream().iterator()
      iterator.next() // First tryAdvance returns true
      iterator.hasNext() // Second tryAdvance returns false and triggers close

      verify(resultSet).close()
    }

    @Test
    fun `ResultSet gets closed when Stream is closed`() {
      // Don't consume the stream, just close it
      many().stream().close()

      verify(resultSet).close()
    }

    @Test
    fun `stream emits all ResultSet rows`() {
      whenever(resultSet.next()).thenReturn(true, true, true, false)
      whenever(resultSet.getString(1)).thenReturn("Alice", "Bob", "Charlie")

      val result = many().stream().toList()

      assertThat(result).containsExactly("Alice", "Bob", "Charlie")
    }
  }
}
