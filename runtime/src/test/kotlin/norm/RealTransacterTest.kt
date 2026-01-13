package norm

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.sql.Connection
import java.sql.Savepoint
import javax.sql.DataSource

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealTransacterTest {

  @Mock
  private lateinit var dataSource: DataSource

  @Mock
  private lateinit var connection: Connection

  @Mock
  private lateinit var savepoint: Savepoint

  private lateinit var driver: NormDriver
  private lateinit var queries: TestQueries

  @BeforeEach
  fun setUp() {
    whenever(dataSource.connection).thenReturn(connection)
    whenever(connection.autoCommit).thenReturn(true)
    whenever(connection.setSavepoint()).thenReturn(savepoint)

    driver = NormDriver(dataSource)
    queries = TestQueries(driver)
  }

  @Nested
  inner class TransactionWithoutResult {

    @Test
    fun `successful transaction commits and manages connection lifecycle`() {
      queries.transaction {
        // Body completes normally
      }

      val inOrder = inOrder(connection)
      inOrder.verify(connection).autoCommit = false
      inOrder.verify(connection).commit()
      inOrder.verify(connection).autoCommit = true
      inOrder.verify(connection).close()
    }

    @Test
    fun `rollback transaction rolls back and manages connection lifecycle`() {
      queries.transaction {
        rollback()
      }

      val inOrder = inOrder(connection)
      inOrder.verify(connection).autoCommit = false
      inOrder.verify(connection).rollback()
      inOrder.verify(connection).autoCommit = true
      inOrder.verify(connection).close()

      verify(connection, never()).commit()
    }

    @Test
    fun `exception causes rollback and propagates with original type`() {
      class CustomException(message: String) : Exception(message)
      val exception = CustomException("Test error")

      val thrown = assertThrows<CustomException> {
        queries.transaction {
          throw exception
        }
      }

      assertThat(thrown).isSameInstanceAs(exception)
      verify(connection).rollback()
      verify(connection, never()).commit()
    }

    @Test
    fun `empty transaction body commits successfully`() {
      queries.transaction { }

      verify(connection).commit()
    }
  }

  @Nested
  inner class TransactionWithResult {

    @Test
    fun `successful transaction commits and returns value`() {
      val result = queries.transactionWithResult {
        "test result"
      }

      assertThat(result).isEqualTo("test result")
      verify(connection).commit()
    }

    @Test
    fun `rollback transaction returns specified value and rolls back`() {
      val result = queries.transactionWithResult {
        rollback("rollback result")
      }

      assertThat(result).isEqualTo("rollback result")
      verify(connection).rollback()
      verify(connection, never()).commit()
    }

    @Test
    fun `exception causes rollback and propagates with original type`() {
      val exception = IllegalStateException("Test")

      val thrown = assertThrows<IllegalStateException> {
        queries.transactionWithResult<String> {
          throw exception
        }
      }

      assertThat(thrown).isSameInstanceAs(exception)
      verify(connection).rollback()
      verify(connection, never()).commit()
    }

    @Test
    fun `complex return types are preserved through commit and rollback`() {
      data class ComplexResult(val id: Int, val name: String)

      val commitResult = queries.transactionWithResult {
        ComplexResult(1, "committed")
      }
      assertThat(commitResult).isEqualTo(ComplexResult(1, "committed"))

      val rollbackResult = queries.transactionWithResult {
        rollback(ComplexResult(2, "rolled back"))
      }
      assertThat(rollbackResult).isEqualTo(ComplexResult(2, "rolled back"))
    }
  }

  @Nested
  inner class NestedTransactionsWithSavepoints {

    @Test
    fun `nested transaction creates savepoint on same connection`() {
      queries.transaction {
        queries.transaction {
          // Inner transaction
        }
      }

      verify(connection).setSavepoint()
      verify(dataSource, times(1)).connection
    }

    @Test
    fun `nested transactions are separate instances`() {
      queries.transaction {
        val outer = this
        queries.transaction {
          val inner = this
          assertThat(inner).isNotSameInstanceAs(outer)
        }
      }
    }

    @Test
    fun `inner rollback isolated to inner - outer continues and commits`() {
      var outerContinued = false
      var codeAfterInnerRollback = 0

      queries.transaction {
        queries.transaction {
          rollback()
        }
        outerContinued = true
        codeAfterInnerRollback++
        codeAfterInnerRollback++
      }

      assertThat(outerContinued).isTrue()
      assertThat(codeAfterInnerRollback).isEqualTo(2)
      verify(connection).rollback(savepoint)
      verify(connection).commit()
    }

    @Test
    fun `inner exception propagates and rolls back all levels`() {
      val exception = RuntimeException("Inner exception")

      val thrown = assertThrows<RuntimeException> {
        queries.transaction {
          queries.transaction {
            throw exception
          }
        }
      }

      assertThat(thrown).isSameInstanceAs(exception)
      verify(connection).rollback(savepoint)
      verify(connection).rollback()
    }

    @Test
    fun `inner success but outer rollback rolls back everything`() {
      queries.transaction {
        queries.transaction {
          // Inner succeeds
        }
        rollback()
      }

      verify(connection).rollback()
      verify(connection, never()).commit()
    }

    @Test
    fun `3+ levels of nesting each creates own savepoint with isolated rollback`() {
      val savepoint2 = mock<Savepoint>()
      whenever(connection.setSavepoint()).thenReturn(savepoint, savepoint2)

      var level2Continued = false
      var level1Continued = false

      queries.transaction {
        queries.transaction {
          queries.transaction {
            rollback()
          }
          level2Continued = true
        }
        level1Continued = true
      }

      assertThat(level2Continued).isTrue()
      assertThat(level1Continued).isTrue()
      verify(connection, times(2)).setSavepoint()
      verify(connection).rollback(savepoint2)
      verify(connection).commit()
    }
  }

  @Nested
  inner class NoEnclosingParameter {

    @Test
    fun `noEnclosing=true succeeds without existing transaction but throws with one`() {
      // Without existing transaction - succeeds
      queries.transaction(noEnclosing = true) { }
      verify(connection).commit()

      // With existing transaction - throws
      assertThrows<IllegalStateException> {
        queries.transaction {
          queries.transaction(noEnclosing = true) {
            // Should not reach here
          }
        }
      }
    }

    @Test
    fun `noEnclosing=false allows nesting (default behavior)`() {
      queries.transaction {
        queries.transaction(noEnclosing = false) {
          // Should succeed
        }
      }

      verify(connection).commit()
    }
  }

  @Nested
  inner class ConnectionManagement {

    @Test
    fun `error when auto-commit already false`() {
      whenever(connection.autoCommit).thenReturn(false)

      assertThrows<IllegalStateException> {
        queries.transaction { }
      }
    }

    @Test
    fun `connection not closed after inner transaction completes`() {
      queries.transaction {
        queries.transaction { }
        verify(connection, never()).close()
      }

      // Only closed after outer completes
      verify(connection).close()
    }
  }

  @Nested
  inner class MixedTransactionTypes {

    @Test
    fun `TransactionWithResult inside TransactionWithoutReturn works`() {
      var innerResult: String? = null

      queries.transaction {
        innerResult = queries.transactionWithResult {
          "from inner"
        }
      }

      assertThat(innerResult).isEqualTo("from inner")
      verify(connection).commit()
    }

    @Test
    fun `TransactionWithoutReturn inside TransactionWithResult works`() {
      var innerExecuted = false

      val result = queries.transactionWithResult {
        queries.transaction {
          innerExecuted = true
        }
        "outer result"
      }

      assertThat(innerExecuted).isTrue()
      assertThat(result).isEqualTo("outer result")
    }
  }
}

private class TestQueries(driver: NormDriver) : RealTransacter(driver)
