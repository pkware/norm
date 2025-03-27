import norm.Many
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.sql.ResultSet

class ManyTest {

  @Nested
  inner class List {
    @Test
    fun `ResultSet gets closed`() {
    }
  }

  @Nested
  inner class Stream {
    @Test
    fun `ResultSet gets closed during forEachRemaining`() {
    }

    @Test
    fun `ResultSet gets closed after last tryAdvance`() {
    }

    @Test
    fun `ResultSet gets closed when Stream is closed`() {
      val resultSet = mock<ResultSet>()
      val many = Many<String>(
        resultSet,
      ) { resultSet -> "Hello" }
      many.stream().close()
      verify(resultSet, times(1)).close()
    }
  }
}
