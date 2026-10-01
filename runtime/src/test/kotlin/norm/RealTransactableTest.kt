package norm

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException

class RealTransactableTest {

  @Test
  fun `throws clear error when ConnectionProvider is not TransactionalConnectionProvider`() {
    val plainProvider = object : ConnectionProvider {
      override fun <R> withConnection(block: (Connection) -> R): R = throw UnsupportedOperationException()
      override fun borrowConnection(): BorrowedConnection = throw UnsupportedOperationException()
    }

    val exception = assertThrows<IllegalStateException> {
      object : RealTransactable(plainProvider) {}
    }
    assertThat(exception.message!!)
      .contains("TransactionalConnectionProvider", "@Transactional")
  }

  @Test
  fun `transaction declares SQLException and IllegalStateException`() {
    val method = RealTransactable::class.java
      .getMethod("transaction", Boolean::class.javaPrimitiveType, Function1::class.java)

    assertThat(method.exceptionTypes.toList()).containsAll(SQLException::class.java, IllegalStateException::class.java)
  }

  @Test
  fun `transactionWithResult declares SQLException and IllegalStateException`() {
    val method = RealTransactable::class.java
      .getMethod("transactionWithResult", Boolean::class.javaPrimitiveType, Function1::class.java)

    assertThat(method.exceptionTypes.toList()).containsAll(SQLException::class.java, IllegalStateException::class.java)
  }
}
