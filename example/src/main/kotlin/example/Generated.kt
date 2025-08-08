package example

import example.ExampleQueries.Raw.ADD_AUTHOR_SQL
import example.ExampleQueries.Raw.DELETE_AUTHOR_SQL
import example.ExampleQueries.Raw.GET_AUTHOR_BY_NAME_SQL
import example.ExampleQueries.Raw.LIST_AUTHORS_SQL
import example.ExampleQueries.Raw.MAP_AUTHOR_BOOKS_SQL
import example.ExampleQueries.Raw.UPDATE_AUTHOR_NAME_SQL
import norm.Many
import norm.RealTransacter
import norm.Transaction
import javax.sql.DataSource

// TODO This was a template of what I thought the output might look like. It's a scratchpad/roadmap of goals.
interface ExampleQueries : Transaction {

  // TODO sqlc calls this a :exec query
  fun mapAuthorBooks(authorId: Int, bookIds: IntArray)

  object Raw {
    const val GET_AUTHOR_BY_NAME_SQL = "SELECT * FROM AUTHORS WHERE name = ?"
    const val LIST_AUTHORS_SQL = "SELECT * FROM AUTHORS"
    const val UPDATE_AUTHOR_NAME_SQL = "UPDATE AUTHORS SET name = ? WHERE id = ?"
    const val ADD_AUTHOR_SQL = "INSERT INTO AUTHORS VALUES (?, ?)"
    const val DELETE_AUTHOR_SQL = "DELETE FROM AUTHORS WHERE id = ?"
    const val MAP_AUTHOR_BOOKS_SQL = "CALL map_books_to_author(?, ?)"
  }
}

public class JdbcExampleQueries(
  dataSource: DataSource
) : RealTransacter(dataSource), ExampleQueries {

  override fun <T> listAuthors(mapper: (Int, String, String, Int) -> T) =
    connection.prepareStatement(LIST_AUTHORS_SQL).use { statement ->
      statement.executeQuery().use { resultSet ->
        Many<T>(resultSet) {
          mapper(
            resultSet.getInt(1), // id
            resultSet.getString(2), // name
            resultSet.getString(3), // email
            resultSet.getInt(4), // revision
          )
        }
      }
    }

  override fun <T : Any> getAuthorByName(
    name: String,
    mapper: (Int, String, String, Int) -> T
  ) = connection.prepareStatement(GET_AUTHOR_BY_NAME_SQL).use { statement ->
    statement.setString(1, name)
    statement.executeQuery().use { resultSet ->
      resultSet.single<T>("getAuthorByName", GET_AUTHOR_BY_NAME_SQL) {
        mapper(
          resultSet.getInt(1), // id
          resultSet.getString(2), // name
          resultSet.getString(3), // email
          resultSet.getInt(4), // revision
        )
      }
    }
  }

  override fun setAuthorName(id: Int, name: String) =
    connection.prepareStatement(UPDATE_AUTHOR_NAME_SQL).use { statement ->
      statement.setString(1, name)
      statement.setInt(2, id)
      statement.executeLargeUpdate()
    }

  override fun <T> addAuthor(name: String, email: String, mapper: (Int, Int) -> T) =
    // TODO The array has to be retrieved from Postgres during code generation. We have to know which columns are generated. If we can't do that, then we shouldn't support this directive b/c it's inefficient. Also, we probably won't know which columns are impacted by triggers, so this is definitely a crutch compared to a :many query with a RETURNING clause.
    connection.prepareStatement(ADD_AUTHOR_SQL, arrayOf("id", "revision")).use { statement ->
      statement.setString(1, name)
      statement.setString(2, email)
      statement.executeUpdate()
      statement.resultSet.use { resultSet ->
        Many<T>(resultSet) {
          mapper(resultSet.getInt(1), resultSet.getInt(2))
        }
      }
    }

  override fun deleteAuthor(id: Int) = connection.prepareStatement(DELETE_AUTHOR_SQL).let { statement ->
    statement.setInt(1, id)
    statement.executeLargeUpdate()
  }

  override fun mapAuthorBooks(authorId: Int, bookIds: IntArray) {
    connection.prepareStatement(MAP_AUTHOR_BOOKS_SQL).use { statement ->
      statement.setInt(1, authorId)
      statement.setArray(2, connection.createArrayOf("int", bookIds))
      statement.execute()
    }
  }
}

@JvmRecord
data class AddAuthor(val id: Int, val revision: Int)
