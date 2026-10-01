package norm

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
}
