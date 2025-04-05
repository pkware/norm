package example

import com.pkware.norm.runtime.NormDriver
import kotlin.jvm.optionals.getOrNull

// I was using this as a playground to shape the API. It's a scratchpad
fun main() {
  val driver: NormDriver
  val queries = PostgresQueries(driver)
  val authorsList = queries.listAuthors().list()
  val authorsToContact = queries.listAuthors { id, name, email, revision -> AuthorContact(name, email) }
  val authorsStartingWithA = queries.listAuthors().stream().use {
    it.filter { it.name.startsWith("a") }.toList()
  }
  val jake = queries.getAuthorByName("jake").get()
  val maybeJake = queries.getAuthorByName("jake").getOrNull()

  println(ExampleQueries.Raw.GET_AUTHOR_BY_NAME_SQL)
  println(jake.name)

  queries.transaction {
    // TODO this should create an unnamed savepoint
    transaction {
      // TODO This should rollback to the savepoint. But then how does the caller know whether or not the transaction succeeded? Should they care?
      rollback()
    }
    // TODO this should create a savepoint
    transaction {
    }
    // TODO This should rollback everything
    rollback()
  }

  queries.transaction {
    for (author in authorsList) {
      queries.addAuthor(author.name, author.email)
    }
  }
}

data class AuthorContact(val name: String, val email: String)
