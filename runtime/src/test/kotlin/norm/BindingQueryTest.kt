package norm

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

@ExtendWith(MockitoExtension::class)
class BindingQueryTest {

  @Mock
  private lateinit var normDriver: NormDriver

  private val rowReader: ResultSet.() -> String = { getString(1) }

  @Nested
  inner class BasicQueryExecution {
    @Test
    fun `single returns result from driver`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      whenever(normDriver.queryOne<String>(any(), any(), any())).thenReturn("Alice")

      val result = query.single()

      assertThat(result).isEqualTo("Alice")
    }

    @Test
    fun `list returns results from driver`() {
      val mockMany = StubMany(listOf("Alice", "Bob"))
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      whenever(normDriver.queryMany<String>(any(), any(), any())).thenReturn(mockMany)

      val result = query.list()

      assertThat(result).containsExactly("Alice", "Bob")
    }
  }

  @Nested
  inner class PositionalParameterBinding {
    @Test
    fun `positional parameters are bound to PreparedStatement in order`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE id = ? AND status = ?", capturedBindings)

      query
        .append(" WHERE id = ?")
        .bind(42)
        .append(" AND status = ?")
        .bind("active")
        .single()

      assertThat(capturedBindings).containsExactly(
        1 to 42,
        2 to "active",
      )
    }

    @Test
    fun `toString does not include bound values`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
        .append(" WHERE id = ?")
        .bind("secret_password_123")

      val result = query.toString()

      assertThat(result).contains("SELECT name FROM users WHERE id = ?")
      assertThat(result).doesNotContain("secret_password_123")
    }
  }

  @Nested
  inner class NamedParameterBinding {
    @Test
    fun `named parameters are bound correctly`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE id = ?", capturedBindings)

      query
        .append(" WHERE id = :userId")
        .bind("userId", 42)
        .single()

      assertThat(capturedBindings).containsExactly(1 to 42)
    }

    @Test
    fun `named parameters are bound in SQL appearance order regardless of bind order`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE status = ? AND id = ?", capturedBindings)

      // Bind in reverse order of SQL appearance for the test to prove it's point
      query
        .append(" WHERE status = :status AND id = :id")
        .bind("id", 42)
        .bind("status", "active")
        .single()

      // Should be bound in SQL order: status first (position 1), then id (position 2)
      assertThat(capturedBindings).containsExactly(
        1 to "active",
        2 to 42,
      )
    }

    @Test
    fun `missing named parameter throws IllegalStateException with parameter name`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)

      val exception = assertThrows<IllegalStateException> {
        query
          .append(" WHERE id = :userId")
          .single()
      }

      assertThat(exception.message).isNotNull().contains("userId")
    }

    @Test
    fun `single named parameter used multiple times is bound at each location`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE name = ? OR alias = ?", capturedBindings)

      query
        .append(" WHERE name = :term")
        .append(" OR alias = :term")
        .bind("term", "Alice")
        .single()

      // The same value should be bound at both positions
      assertThat(capturedBindings).containsExactly(
        1 to "Alice",
        2 to "Alice",
      )
    }

    @Test
    fun `binding same named parameter multiple times uses the last value`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE id = ?", capturedBindings)

      query
        .append(" WHERE id = :userId")
        .bind("userId", 1)
        .bind("userId", 2)
        .bind("userId", 42)
        .single()

      assertThat(capturedBindings).containsExactly(1 to 42)
    }

    @Test
    fun `unused bound parameters are ignored without error`() {
      val capturedBindings = mutableListOf<Pair<Int, Any?>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE id = ?", capturedBindings)

      query
        .append(" WHERE id = :userId")
        .bind("userId", 42)
        .bind("unusedParam", "ignored")

      assertDoesNotThrow { query.single() }

      // Only userId is bound; unusedParam is silently ignored
      assertThat(capturedBindings).containsExactly(1 to 42)
    }

    @Test
    fun `toString does not include bound values`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
        .append(" WHERE id = :userId")
        .bind("userId", "secret_api_key_xyz")

      val result = query.toString()

      assertThat(result).contains("SELECT name FROM users WHERE id = :userId")
      assertThat(result).doesNotContain("secret_api_key_xyz")
    }
  }

  @Nested
  inner class ParameterMixingValidation {
    @Test
    fun `binding named parameter after positional throws IllegalStateException`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      query.bind(42)

      val exception = assertThrows<IllegalStateException> {
        query.bind("name", "Alice")
      }

      assertThat(exception.message).isNotNull().contains("Cannot mix")
    }

    @Test
    fun `binding positional parameter after named throws IllegalStateException`() {
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      query.bind("name", "Alice")

      val exception = assertThrows<IllegalStateException> {
        query.bind(42)
      }

      assertThat(exception.message).isNotNull().contains("Cannot mix")
    }
  }

  @Nested
  inner class NullParameterHandling {
    @Test
    fun `null positional parameter uses setNull with Types NULL`() {
      val capturedNulls = mutableListOf<Pair<Int, Int>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE email = ?", capturedNulls = capturedNulls)

      query.append(" WHERE email = ?").bind(null).single()

      assertThat(capturedNulls).containsExactly(1 to Types.NULL)
    }

    @Test
    fun `null named parameter uses setNull with Types NULL`() {
      val capturedNulls = mutableListOf<Pair<Int, Int>>()
      val query = BindingQuery("SELECT name FROM users", rowReader, normDriver)
      setupDriverToCaptureInputs("SELECT name FROM users WHERE email = ?", capturedNulls = capturedNulls)

      query
        .append(" WHERE email = :email")
        .bind("email", null)
        .single()

      assertThat(capturedNulls).containsExactly(1 to Types.NULL)
    }
  }

  /**
   * @param expectedQuery The SQL that we expect to execute.
   * @param capturedBindings The list into which captured bindings should be placed.
   * @param capturedNulls The list into which captured `null` bindings should be placed.
   */
  private fun setupDriverToCaptureInputs(
    @Language("PostgreSQL") expectedQuery: String,
    capturedBindings: MutableList<Pair<Int, Any?>> = mutableListOf(),
    capturedNulls: MutableList<Pair<Int, Int>> = mutableListOf(),
  ) {
    whenever(normDriver.queryOne<String>(eq(expectedQuery), any(), any())).thenAnswer { invocation ->
      val binder = invocation.getArgument<(PreparedStatement.() -> Unit)?>(2)
      val mockStatement = RecordingPreparedStatement(capturedBindings, capturedNulls)
      binder?.invoke(mockStatement)
      "result"
    }
  }

  /**
   * A minimal PreparedStatement stub that records setObject and setNull calls.
   */
  private class RecordingPreparedStatement(
    private val objectCapture: MutableList<Pair<Int, Any?>> = mutableListOf(),
    private val nullCapture: MutableList<Pair<Int, Int>> = mutableListOf(),
  ) : PreparedStatement by NoOpPreparedStatement {
    override fun setObject(parameterIndex: Int, x: Any?) {
      objectCapture.add(parameterIndex to x)
    }

    override fun setNull(parameterIndex: Int, sqlType: Int) {
      nullCapture.add(parameterIndex to sqlType)
    }
  }
}

/**
 * Simple Many implementation for testing that returns a fixed list.
 */
private class StubMany<T>(private val items: List<T>) : Many<T> {
  override fun <C : MutableCollection<T>> collection(factory: () -> C): C {
    val result = factory()
    result.addAll(items)
    return result
  }

  override fun firstOrNull(): T? = items.firstOrNull()
}

/**
 * A no-op PreparedStatement for delegation.
 */
@Suppress("EmptyFunctionBlock") // We're inheriting but don't need most implementations
private object NoOpPreparedStatement : PreparedStatement {
  override fun executeQuery() = throw UnsupportedOperationException()
  override fun executeUpdate() = throw UnsupportedOperationException()
  override fun setNull(parameterIndex: Int, sqlType: Int) {}
  override fun setBoolean(parameterIndex: Int, x: Boolean) {}
  override fun setByte(parameterIndex: Int, x: Byte) {}
  override fun setShort(parameterIndex: Int, x: Short) {}
  override fun setInt(parameterIndex: Int, x: Int) {}
  override fun setLong(parameterIndex: Int, x: Long) {}
  override fun setFloat(parameterIndex: Int, x: Float) {}
  override fun setDouble(parameterIndex: Int, x: Double) {}
  override fun setBigDecimal(parameterIndex: Int, x: java.math.BigDecimal?) {}
  override fun setString(parameterIndex: Int, x: String?) {}
  override fun setBytes(parameterIndex: Int, x: ByteArray?) {}
  override fun setDate(parameterIndex: Int, x: java.sql.Date?) {}
  override fun setTime(parameterIndex: Int, x: java.sql.Time?) {}
  override fun setTimestamp(parameterIndex: Int, x: java.sql.Timestamp?) {}
  override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}

  @Deprecated("Deprecated in Java")
  override fun setUnicodeStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {
  }

  override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Int) {}
  override fun clearParameters() {}
  override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) {}
  override fun setObject(parameterIndex: Int, x: Any?) {}
  override fun execute() = false
  override fun addBatch() {}
  override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Int) {}
  override fun setRef(parameterIndex: Int, x: java.sql.Ref?) {}
  override fun setBlob(parameterIndex: Int, x: java.sql.Blob?) {}
  override fun setClob(parameterIndex: Int, x: java.sql.Clob?) {}
  override fun setArray(parameterIndex: Int, x: java.sql.Array?) {}
  override fun getMetaData() = throw UnsupportedOperationException()
  override fun setDate(parameterIndex: Int, x: java.sql.Date?, cal: java.util.Calendar?) {}
  override fun setTime(parameterIndex: Int, x: java.sql.Time?, cal: java.util.Calendar?) {}
  override fun setTimestamp(parameterIndex: Int, x: java.sql.Timestamp?, cal: java.util.Calendar?) {}
  override fun setNull(parameterIndex: Int, sqlType: Int, typeName: String?) {}
  override fun setURL(parameterIndex: Int, x: java.net.URL?) {}
  override fun getParameterMetaData() = throw UnsupportedOperationException()
  override fun setRowId(parameterIndex: Int, x: java.sql.RowId?) {}
  override fun setNString(parameterIndex: Int, value: String?) {}
  override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?, length: Long) {}
  override fun setNClob(parameterIndex: Int, value: java.sql.NClob?) {}
  override fun setClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
  override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?, length: Long) {}
  override fun setNClob(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
  override fun setSQLXML(parameterIndex: Int, xmlObject: java.sql.SQLXML?) {}
  override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {}
  override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
  override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?, length: Long) {}
  override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?, length: Long) {}
  override fun setAsciiStream(parameterIndex: Int, x: java.io.InputStream?) {}
  override fun setBinaryStream(parameterIndex: Int, x: java.io.InputStream?) {}
  override fun setCharacterStream(parameterIndex: Int, reader: java.io.Reader?) {}
  override fun setNCharacterStream(parameterIndex: Int, value: java.io.Reader?) {}
  override fun setClob(parameterIndex: Int, reader: java.io.Reader?) {}
  override fun setBlob(parameterIndex: Int, inputStream: java.io.InputStream?) {}
  override fun setNClob(parameterIndex: Int, reader: java.io.Reader?) {}
  override fun executeQuery(sql: String?) = throw UnsupportedOperationException()
  override fun executeUpdate(sql: String?) = throw UnsupportedOperationException()
  override fun close() {}
  override fun getMaxFieldSize() = 0
  override fun setMaxFieldSize(max: Int) {}
  override fun getMaxRows() = 0
  override fun setMaxRows(max: Int) {}
  override fun setEscapeProcessing(enable: Boolean) {}
  override fun getQueryTimeout() = 0
  override fun setQueryTimeout(seconds: Int) {}
  override fun cancel() {}
  override fun getWarnings() = null
  override fun clearWarnings() {}
  override fun setCursorName(name: String?) {}
  override fun execute(sql: String?) = false
  override fun getResultSet() = throw UnsupportedOperationException()
  override fun getUpdateCount() = 0
  override fun getMoreResults() = false
  override fun setFetchDirection(direction: Int) {}
  override fun getFetchDirection() = 0
  override fun setFetchSize(rows: Int) {}
  override fun getFetchSize() = 0
  override fun getResultSetConcurrency() = 0
  override fun getResultSetType() = 0
  override fun addBatch(sql: String?) {}
  override fun clearBatch() {}
  override fun executeBatch() = intArrayOf()
  override fun getConnection() = throw UnsupportedOperationException()
  override fun getMoreResults(current: Int) = false
  override fun getGeneratedKeys() = throw UnsupportedOperationException()
  override fun executeUpdate(sql: String?, autoGeneratedKeys: Int) = 0
  override fun executeUpdate(sql: String?, columnIndexes: IntArray?) = 0
  override fun executeUpdate(sql: String?, columnNames: Array<out String>?) = 0
  override fun execute(sql: String?, autoGeneratedKeys: Int) = false
  override fun execute(sql: String?, columnIndexes: IntArray?) = false
  override fun execute(sql: String?, columnNames: Array<out String>?) = false
  override fun getResultSetHoldability() = 0
  override fun isClosed() = false
  override fun setPoolable(poolable: Boolean) {}
  override fun isPoolable() = false
  override fun closeOnCompletion() {}
  override fun isCloseOnCompletion() = false
  override fun <T : Any?> unwrap(iface: Class<T>?) = throw UnsupportedOperationException()
  override fun isWrapperFor(iface: Class<*>?) = false
}
