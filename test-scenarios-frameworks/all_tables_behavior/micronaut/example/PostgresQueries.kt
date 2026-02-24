package example

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.Any
import kotlin.Int
import kotlin.String
import kotlin.jvm.Throws
import norm.NormDriver
import norm.RealTransacter

public class PostgresQueries(
  driver: NormDriver,
) : RealTransacter(driver),
    Queries {
  @Throws(SQLException::class)
  override fun <T : Any> getAuthor(id: Int, mapper: (
    id: Int,
    name: String,
    email: String?,
  ) -> T): T {
    val sql = "SELECT * FROM author WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getString(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> getBook(id: Int, mapper: (
    id: Int,
    title: String,
    authorId: Int,
  ) -> T): T {
    val sql = "SELECT * FROM book WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        getInt(3),
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }
}
