package example

import com.example.JsonData
import io.micronaut.context.`annotation`.Requires
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource
import kotlin.Any
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.Iterable
import kotlin.jvm.Throws
import norm.ColumnAdapter
import norm.ConnectionProvider
import norm.NormDriver
import norm.combineExecBatchResults

@Singleton
@Requires(missingBeans = [Queries::class])
@Requires(beans = [DataSource::class])
public class PostgresQueries(
  connectionProvider: ConnectionProvider,
  private val jsonbAdapter: ColumnAdapter<JsonData, String>,
  private val emailAddressAdapter: ColumnAdapter<EmailAddress, String> = EmailAddressAdapter(),
  private val moodAdapter: ColumnAdapter<Mood, String> = MoodAdapter(),
) : Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

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
    author_id: Int,
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

  @Throws(SQLException::class)
  override fun addAuthor(name: String, email: String?) {
    val sql = "INSERT INTO author(name, email) VALUES (?, ?)"
    driver.execute(sql) {
      setString(1, name)
      setString(2, email)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> addAuthor(
    stream: Iterable<Input>,
    name: Input.() -> String,
    email: Input.() -> String?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO author(name, email) VALUES (?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.name())
        setString(2, entry.email())
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }

  @Throws(SQLException::class)
  override fun <T : Any> getPersonById(id: Int, mapper: (
    id: Int,
    name: String,
    contact_email: EmailAddress,
    current_mood: Mood,
    bio: JsonData?,
  ) -> T): T {
    val sql = "SELECT * FROM person WHERE id = ?"
    val rowReader: ResultSet.() -> T = {
      mapper(
        getInt(1),
        getString(2),
        emailAddressAdapter.decode(getString(3)),
        moodAdapter.decode(getString(4)),
        getString(5)?.let { jsonbAdapter.decode(it) },
      )
    }
    return driver.queryOne(sql, rowReader) {
      setInt(1, id)
    }
  }

  @Throws(SQLException::class)
  override fun createPerson(
    name: String,
    contact_email: EmailAddress,
    current_mood: Mood,
    bio: JsonData?,
  ) {
    val sql = "INSERT INTO person (name, contact_email, current_mood, bio) VALUES (?, ?, ?, ?)"
    driver.execute(sql) {
      setString(1, name)
      setString(2, emailAddressAdapter.encode(contact_email))
      setObject(3, moodAdapter.encode(current_mood), Types.OTHER)
      bio?.let { setObject(4, jsonbAdapter.encode(it), Types.OTHER) } ?: setNull(4, Types.OTHER)
      execute()
    }
  }

  @Throws(SQLException::class)
  override fun <Input : Any> createPerson(
    stream: Iterable<Input>,
    name: Input.() -> String,
    contact_email: Input.() -> EmailAddress,
    current_mood: Input.() -> Mood,
    bio: Input.() -> JsonData?,
    batchSize: Int,
  ): IntArray {
    val sql = "INSERT INTO person (name, contact_email, current_mood, bio) VALUES (?, ?, ?, ?)"
    return driver.execute(sql) {
      var totalCount = 0
      var batchCount = 0
      val results = mutableListOf<IntArray>()
      for (entry in stream) {
        setString(1, entry.name())
        setString(2, emailAddressAdapter.encode(entry.contact_email()))
        setObject(3, moodAdapter.encode(entry.current_mood()), Types.OTHER)
        entry.bio()?.let { setObject(4, jsonbAdapter.encode(it), Types.OTHER) } ?: setNull(4, Types.OTHER)
        addBatch()
        batchCount++
        if (batchCount == batchSize) {
          executeBatch()
          batchCount = 0
        }
      }
      if (batchCount > 0) {
        results.add(executeBatch())
        totalCount += batchCount
      }
      combineExecBatchResults(results, totalCount, batchSize)
    }
  }
}
