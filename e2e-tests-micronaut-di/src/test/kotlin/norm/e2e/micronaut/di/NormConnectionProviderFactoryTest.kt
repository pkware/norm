package norm.e2e.micronaut.di

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.index
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import example.PostgresQueries
import io.micronaut.context.ApplicationContext
import norm.ConnectionProvider
import norm.Transactable
import norm.TransactionalConnectionProvider
import org.junit.jupiter.api.Test

class NormConnectionProviderFactoryTest {
  @Test
  fun `generated queries are the only Transactable bean`() {
    ApplicationContext.run().use { context ->
      val candidates = context.getBeansOfType(Transactable::class.java).toList()

      assertThat(candidates).hasSize(1)
      assertThat(candidates).index(0).isInstanceOf<PostgresQueries>()
    }
  }

  @Test
  fun `Transactable injection point resolves to the generated queries`() {
    ApplicationContext.run().use { context ->
      assertThat(context.getBean(Transactable::class.java)).isInstanceOf<PostgresQueries>()
    }
  }

  @Test
  fun `connection provider is resolvable under both of its exposed types`() {
    ApplicationContext.run().use { context ->
      val byProviderType = context.getBean(TransactionalConnectionProvider::class.java)
      val byInterface = context.getBean(ConnectionProvider::class.java)

      assertThat(byInterface).isSameInstanceAs(byProviderType)
    }
  }
}
