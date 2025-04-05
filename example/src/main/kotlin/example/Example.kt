package example

import org.postgresql.jdbc.PgConnection
import kotlin.jvm.optionals.getOrNull

// I was using this as a playground to shape the API. It's a scratchpad
fun main() {
  val connection: PgConnection
  val queries = JdbcExampleQueries(connection)
  val authorsList = queries.listAuthors().list()
  val authorsToContact = queries.listAuthors { id, name, email, revision -> AuthorContact(name, email) }
  val authorsStartingWithA = queries.listAuthors().stream().use {
    it.filter { it.name.startsWith("a") }.toList()
  }
  val jake = queries.getAuthorByName("jake").get()
  val maybeJake = queries.getAuthorByName("jake").getOrNull()

  println(ExampleQueries.Raw.GET_AUTHOR_BY_NAME_SQL)
  println(jake.name)

  queries.transaction { outer ->
    outer.transaction { inner ->

    }
    outer.transaction { inner ->

    }
  }

  queries.transaction {
    for (author in authorsList) {
      queries.addAuthor(author.name, author.email)
    }
  }
}

data class AuthorContact(val name: String, val email: String)
