package norm

import assertk.assertThat
import assertk.assertions.containsExactly
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

@ExtendWith(MockitoExtension::class)
class AdaptedArraysTest {

  // Simulates an adapter where the DB stores uppercase (wire type) and the app uses lowercase (application type).
  private val stringIdentityAdapter = object : ColumnAdapter<String, String> {
    override fun decode(databaseValue: String): String = databaseValue.lowercase()
    override fun encode(value: String): String = value.uppercase()
  }

  private val intToStringAdapter = object : ColumnAdapter<Int, String> {
    override fun decode(databaseValue: String): Int = databaseValue.toInt()
    override fun encode(value: Int): String = value.toString()
  }

  @Nested
  inner class DecodeArray {

    @Test
    fun `decodes String array elements through adapter`() {
      val jdbcArray = FakeJdbcArray(arrayOf("HAPPY", "SAD", null))

      val result = jdbcArray.decodeArray(stringIdentityAdapter)

      assertThat(result.toList()).containsExactly("happy", "sad", null)
    }

    @Test
    fun `null element is preserved as null in decoded array`() {
      val jdbcArray = FakeJdbcArray(arrayOf<String?>(null, null))

      val result = jdbcArray.decodeArray(stringIdentityAdapter)

      assertThat(result.toList()).containsExactly(null, null)
    }

    @Test
    fun `decodes Int-backed domain array elements through adapter`() {
      val jdbcArray = FakeJdbcArray(arrayOf<Int?>(1, null, 3))

      val result = jdbcArray.decodeArray(object : ColumnAdapter<String, Int> {
        override fun decode(databaseValue: Int): String = "item-$databaseValue"
        override fun encode(value: String): Int = value.removePrefix("item-").toInt()
      })

      assertThat(result.toList()).containsExactly("item-1", null, "item-3")
    }

    @Test
    fun `decodes empty array to empty result`() {
      val jdbcArray = FakeJdbcArray(emptyArray<String?>())

      val result = jdbcArray.decodeArray(stringIdentityAdapter)

      assertThat(result.toList()).containsExactly()
    }
  }

  @Nested
  inner class EncodeToSqlArray(@Mock private val connection: Connection, @Mock private val sqlArray: java.sql.Array) {

    @Test
    fun `encodes application values through adapter and calls createArrayOf`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      arrayOf("Hello", "World", null).encodeToSqlArray(connection, "my_type", stringIdentityAdapter)

      verify(connection).createArrayOf(eq("my_type"), eq(arrayOf("HELLO", "WORLD", null)))
    }

    @Test
    fun `null elements are preserved as null in encoded array`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      arrayOf<String?>(null, "Hello", null).encodeToSqlArray(connection, "t", stringIdentityAdapter)

      verify(connection).createArrayOf(eq("t"), eq(arrayOf<String?>(null, "HELLO", null)))
    }

    @Test
    fun `encodes Int-keyed application type to String wire type`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      arrayOf(1, null, 3).encodeToSqlArray(connection, "numbers", intToStringAdapter)

      verify(connection).createArrayOf(eq("numbers"), eq(arrayOf("1", null, "3")))
    }

    @Test
    fun `encodes empty array correctly`() {
      whenever(connection.createArrayOf(any(), any())).thenReturn(sqlArray)

      emptyArray<String?>().encodeToSqlArray(connection, "t", stringIdentityAdapter)

      verify(connection).createArrayOf(eq("t"), eq(emptyArray()))
    }
  }

  /**
   * Minimal [java.sql.Array] implementation that returns a fixed object array.
   *
   * The real JDBC driver returns `String[]` for enum/text arrays, `Integer[]` for int4 arrays,
   * etc. We use `Array<T?>` directly here since the JVM type is the same at runtime.
   */
  private class FakeJdbcArray(private val elements: Array<*>) : java.sql.Array {
    override fun getBaseTypeName(): String = "fake"
    override fun getBaseType(): Int = 0
    override fun getArray(): Any = elements
    override fun getArray(map: MutableMap<String, Class<*>>?): Any = elements
    override fun getArray(index: Long, count: Int): Any = elements
    override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?): Any = elements
    override fun getResultSet(): java.sql.ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(map: MutableMap<String, Class<*>>?): java.sql.ResultSet =
      throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int): java.sql.ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?): java.sql.ResultSet =
      throw UnsupportedOperationException()
    override fun free() = Unit
  }
}
