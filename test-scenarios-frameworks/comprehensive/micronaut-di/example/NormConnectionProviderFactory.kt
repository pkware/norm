package example

import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import norm.ConnectionProvider
import norm.TransactionalConnectionProvider
import javax.sql.DataSource

@Factory
public class NormConnectionProviderFactory {
  @Singleton
  @Requires(missingBeans = [ConnectionProvider::class])
  @Requires(beans = [DataSource::class])
  public fun connectionProvider(dataSource: DataSource): TransactionalConnectionProvider =
    TransactionalConnectionProvider(dataSource)
}
