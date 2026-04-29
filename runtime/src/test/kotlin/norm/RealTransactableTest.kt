package norm

import assertk.assertThat
import assertk.assertions.contains
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection

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
}
