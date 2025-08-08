package example

import norm.NormDriver
import org.postgresql.ds.PGSimpleDataSource

fun main() {
  val queries = setupNorm()

  // Single item retrievals are trivial with Norm.
  // Generated entities are Java records/Kotlin data classes.
  val george = queries.getAuthorByName("George R.R. Martin")

  // Custom projections are effortless, correctly typed, and have the right nullability.
  // Here a LEFT JOIN means columns from the book table can be null.
  // Note that Norm doesn't do name mangling. Entity members have the same name as in the database.
  val georgesBestSeller = queries.authorAndMostPopularBook("George R.R. Martin")
  if (georgesBestSeller.title == null) {
    println("George R.R. Martin didn't write any books")
  } else {
    println("George R.R. Martin's most popular book was '${georgesBestSeller.title}' with ${georgesBestSeller.copies_sold} sold.")
  }

  // Queries that result in multiple rows have multiple result-handling options.
  // list() materializes the entire result set into a list.
  val authorsList = queries.listAuthors().list()

  // stream() materializes the results lazily, but holds a reference to the JDBC Connection. Close the Stream when done
  // with it.
  queries.listAuthors().stream().use { stream ->
    stream.forEach(::println)
  }

  // distinct() makes it easy to get a Set, and since projections are records/data classes, this will always be safe!
  // But note that it might not be what you want, and SQL DISTINCT is probably better. See the Javadoc.
  val uniqueBooks = queries.listBooks().distinct()

  // You can map to a collection of your choosing as well.
  val books = queries.listBooks().collection(::mutableListOf)

  // And even add the results directly to an existing collection. Here we add the books to the books list a second time.
  queries.listBooks().collection { books }

  // 0 or 1 results are easy too.
  queries.mostPopularBook("George R.R. Martin").firstOrNull()?.let {
    println("George R.R. Martin's most popular book was '${it.title}' with ${it.copies_sold} sold.")
  }

  // Any query can be mapped to a custom type. This makes it easy to use existing DTOs, modify mutable models, etc.
  val authorContacts = queries.listAuthors { id, name, email, revision ->
    AuthorContact(name, email)
  }.list()

  // DMLs are also supported.
  require(queries.setEmailForName("stephenking@example.com", "Stephen King") == 1)

  // Efficient batch updates are easy and have no overhead compared to manual batching.
  val authorsToAdd = listOf(
    AuthorContact("J.K. Rowling", "harrypotterauthor@example.com"),
    AuthorContact("Dr. Seuss", "whoswho@example.com"),
    AuthorContact("Leo Tolstoy", null),
  )
  queries.addAuthor(authorsToAdd, AuthorContact::name, AuthorContact::email)

  // Transactions are naturally scoped, and are tied to the initiating thread.
  queries.transaction {
    for (author in authorsToAdd) {
      queries.addAuthor(author.name, author.email)
    }
  }

  // Nested transactions are also available, and incremental rollbacks too.
  queries.transaction {
    // TODO This creates an unnamed savepoint.
    transaction {
      // TODO Rollback to the savepoint. But then how does the caller know whether or not the transaction succeeded? Should they care?
      rollback()
    }
    // TODO this should create an unnamed savepoint
    transaction {
    }
    // TODO Rollback everything.
    rollback()
  }

}

private fun setupNorm(): PostgresQueries {
  // You probably want to use a connection pool like HikariCP.
  val dataSource = PGSimpleDataSource().apply {
    setURL("jdbc:postgresql://localhost:5432/postgres")
    user = "postgres"
    password = ""
  }

  // Treat the Norm instance as a singleton. Norm is thread-safe.
  val norm = NormDriver(dataSource)
  // Creating new instances of Query classes is cheap, but they are also thread-safe and can be treated as a singleton.
  val queries = PostgresQueries(norm)
  return queries
}

data class AuthorContact(val name: String, val email: String?)
