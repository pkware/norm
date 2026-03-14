package norm.e2e.micronaut

import io.micronaut.context.annotation.Context
import io.micronaut.data.connection.ConnectionStatus
import io.micronaut.data.connection.support.AbstractConnectionOperations
import io.micronaut.data.connection.support.ConnectionCustomizer
import jakarta.inject.Singleton
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/**
 * A [ConnectionCustomizer] that counts how many times it is invoked.
 *
 * Used by [NormMicronautConnectionCustomizerTest] to verify that Norm's `MicronautConnectionProvider`
 * properly delegates to [ConnectionOperations.execute][io.micronaut.data.connection.ConnectionOperations.execute],
 * which is the call path that applies customizers.
 *
 * Customizers must self-register via [AbstractConnectionOperations.addConnectionCustomizer] — they are not
 * automatically discovered by the DI container.
 */
@Singleton
@Context
class InvocationCountingCustomizer(connectionOperations: AbstractConnectionOperations<Connection>) :
  ConnectionCustomizer<Connection> {

  init {
    connectionOperations.addConnectionCustomizer(this)
  }

  val count = AtomicInteger(0)

  override fun <R> intercept(
    operation: Function<ConnectionStatus<Connection>, R>,
  ): Function<ConnectionStatus<Connection>, R> = Function { status ->
    count.incrementAndGet()
    operation.apply(status)
  }
}
