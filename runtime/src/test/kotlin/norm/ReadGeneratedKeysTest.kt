package norm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.sql.ResultSet

/** Tests for [readGeneratedKeys] that drains a ResultSet into a mutable list. */
@ExtendWith(MockitoExtension::class)
class ReadGeneratedKeysTest {
  @Mock
  lateinit var resultSet: ResultSet

  @Test
  fun `drains multiple rows`() {
    whenever(resultSet.next()).thenReturn(true, true, true, false)
    whenever(resultSet.getInt(1)).thenReturn(10, 20, 30)

    val destination = mutableListOf<Int>()
    readGeneratedKeys(resultSet, { getInt(1) }, destination)

    assertThat(destination).containsExactly(10, 20, 30)
  }

  @Test
  fun `produces empty list when ResultSet is exhausted`() {
    whenever(resultSet.next()).thenReturn(false)

    val destination = mutableListOf<Int>()
    readGeneratedKeys(resultSet, { 0 }, destination)

    assertThat(destination).isEmpty()
  }

  @Test
  fun `appends to existing contents`() {
    whenever(resultSet.next()).thenReturn(true, false)
    whenever(resultSet.getInt(1)).thenReturn(99)

    val destination = mutableListOf(1, 2, 3)
    readGeneratedKeys(resultSet, { getInt(1) }, destination)

    assertThat(destination).containsExactly(1, 2, 3, 99)
  }
}
