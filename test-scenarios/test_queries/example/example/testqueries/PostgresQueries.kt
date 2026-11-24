package example.testqueries

import java.sql.ResultSet
import kotlin.Any
import kotlin.Int
import kotlin.String
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.Query
import norm.RealTransactable

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> listAuthors(mapper: (id: Int, name: String) -> T, processor: ManyProcessor<T, Return>): Return {
    val sql = "SELECT * FROM author ORDER BY id"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
      )
    }
    return processor.invoke(sql, rowReader, null)
  }

  override fun <T : Any> listAuthors(mapper: (id: Int, name: String) -> T): Many<T> = listAuthors(mapper, driver::queryMany)

  override fun <T : Any> listAuthorsDynamically(mapper: (id: Int, name: String) -> T): Query<T> = listAuthors(mapper) { sql, rowReader, _ -> driver.dynamic(sql, rowReader) }
}
