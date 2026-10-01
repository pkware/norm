package norm.e2e.micronaut.di

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.lang.reflect.Proxy
import javax.sql.DataSource

/** Satisfies the generated connection provider's `DataSource` requirement without a database. */
@Factory
class UnusableDataSourceFactory {
  /** Returns a [DataSource] whose methods all throw [UnsupportedOperationException]. */
  @Singleton
  fun dataSource(): DataSource = Proxy.newProxyInstance(
    DataSource::class.java.classLoader,
    arrayOf(DataSource::class.java),
  ) { _, _, _ -> throw UnsupportedOperationException("This module has no database.") } as DataSource
}
