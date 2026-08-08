package norm

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet

@ExtendWith(MockitoExtension::class)
class PlainArraysTest {

  @Nested
  inner class ToSqlArray(@Mock private val connection: Connection, @Mock private val sqlArray: java.sql.Array) {

    @Test
    fun `passes element type name and array through to createArrayOf`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      arrayOf("a", null, "b").toSqlArray(connection, "jsonb")

      verify(connection).createArrayOf(eq("jsonb"), eq(arrayOf("a", null, "b")))
    }

    @Test
    fun `empty array is passed through unchanged`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      emptyArray<String?>().toSqlArray(connection, "text")

      verify(connection).createArrayOf(eq("text"), eq(emptyArray()))
    }

    @Test
    fun `returns the array the connection created`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      val result = arrayOf(1, 2).toSqlArray(connection, "int4")

      assertThat(result).isEqualTo(sqlArray)
    }
  }

  @Nested
  inner class MapElements(@Mock private val elementRows: ResultSet) {

    @Test
    fun `applies read once per element row`() {
      whenever(elementRows.next()).thenReturn(true, true, false)
      whenever(elementRows.getString(2)).thenReturn("first").thenReturn("second")

      val result = FakeJdbcArray(elementRows).mapElements { getString(2) }

      assertThat(result.toList()).containsExactly("first", "second")
    }

    @Test
    fun `preserves a NULL element as null`() {
      whenever(elementRows.next()).thenReturn(true, true, false)
      whenever(elementRows.getString(2)).thenReturn(null).thenReturn("present")

      val result = FakeJdbcArray(elementRows).mapElements { getString(2) }

      assertThat(result.toList()).containsExactly(null, "present")
    }

    @Test
    fun `empty array produces an empty result`() {
      whenever(elementRows.next()).thenReturn(false)

      val result = FakeJdbcArray(elementRows).mapElements { getString(2) }

      assertThat(result.toList()).isEmpty()
    }

    @Test
    fun `closes the element ResultSet`() {
      whenever(elementRows.next()).thenReturn(false)

      FakeJdbcArray(elementRows).mapElements { getString(2) }

      verify(elementRows).close()
    }
  }

  /**
   * Minimal [java.sql.Array] that exposes a caller-supplied element [ResultSet].
   *
   * Only [getResultSet] is implemented — [mapElements] must not touch the bulk [getArray] path,
   * so every other accessor throws to make an accidental dependency on it fail loudly.
   */
  private class FakeJdbcArray(private val elementRows: ResultSet) : java.sql.Array {
    override fun getBaseTypeName(): String = "fake"
    override fun getBaseType(): Int = 0
    override fun getArray(): Any = throw UnsupportedOperationException()
    override fun getArray(map: MutableMap<String, Class<*>>?): Any = throw UnsupportedOperationException()
    override fun getArray(index: Long, count: Int): Any = throw UnsupportedOperationException()
    override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?): Any =
      throw UnsupportedOperationException()
    override fun getResultSet(): ResultSet = elementRows
    override fun getResultSet(map: MutableMap<String, Class<*>>?): ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int): ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?): ResultSet =
      throw UnsupportedOperationException()
    override fun free() = Unit
  }
}
