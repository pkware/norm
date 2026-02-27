package example

import io.micronaut.context.ApplicationContext
import io.micronaut.transaction.TransactionDefinition
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.sql.Connection
import kotlin.random.Random

fun main() {
  // Start Micronaut and get the example bean.
  // In a real application, Micronaut manages the lifecycle automatically.
  ApplicationContext.run().use { context ->
    context.getBean(Example::class.java).run()
  }
}

// Micronaut AOP requires `open` for @Transactional methods to work (compile-time subclassing).
@Singleton
open class Example(
  private val queries: PostgresQueries,
  private val transactionOperations: TransactionOperations<Connection>,
) {

  fun run() {
    // Single item retrievals are trivial with Norm.
    // Generated entities are Java records/Kotlin data classes.
    val george = queries.getAuthorByName("George R.R. Martin")
    // Type-mapped columns come back as the custom Kotlin type, not the raw wire type.
    val georgeStatus: AuthorStatus = george.status  // AuthorStatus.ACTIVE, not the raw String "active"

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

    // Norm provides a Query API for dynamic SQL. When possible, prefer the static API as it's type-safe,
    // verified at compile time, and slightly more efficient. Norm's Query API is more memory and CPU efficient than
    // libraries that use reflection-based mapping such as Spring's JdbcClient.
    val query = queries.listAuthorsDynamically()
      .append(" WHERE")
    if (Random.nextBoolean()) {
      query
        .append(" name LIKE '%:name%'")
        .bind("name", "George")
    } else {
      query
        .append(" startsWith(name, :letter)")
        .bind("letter", "G")
    }
    val dynamicAuthors = query.list()

    // Kotlin makes dynamic query building more pleasant.
    val kotlinDynamicAuthors = queries.listAuthorsDynamically().run {
      append(" WHERE")
      if (Random.nextBoolean()) {
        append(" name LIKE '%:name%'")
        bind("name", "George")
      } else {
        append(" startsWith(name, :letter)")
        bind("letter", "G")
      }
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

    // By default, Norm generates repository-style CRUD methods for each table. These are synthesized from your schema.

    // insertAuthor() accepts only the "insertable" columns (excluding auto-increment, defaults, and
    // generated columns) and returns the database-assigned values via RETURNING.
    // `status` has a DEFAULT so it's excluded from parameters — but it comes back as AuthorStatus
    // in the RETURNING result, already decoded by AuthorStatusAdapter.
    val inserted = queries.insertAuthor("Toni Morrison", "morrison@example.com")
    println("Inserted author with id=${inserted.id}, revision=${inserted.revision}, status=${inserted.status}")

    // findAuthorById() returns a Many<Author>, the same Author type used by hand-written queries.
    val found = queries.findAuthorById(inserted.id).firstOrNull()
    println("Found: ${found?.name}")

    // existsAuthorById() is a quick existence check — no need to fetch the full row.
    val exists = queries.existsAuthorById(inserted.id)
    println("Author exists: $exists")

    // findAllAuthor() and countAuthor() cover common listing and counting patterns.
    val allAuthors = queries.findAllAuthor().list()
    val authorCount = queries.countAuthor()
    println("Total authors: $authorCount")

    // deleteAuthorById() returns the number of deleted rows, just like a hand-written :execrows query.
    val deleted = queries.deleteAuthorById(inserted.id)

    // Programmatic: scoped transaction wrapping multiple queries.
    // All queries inside the block share one connection and commit atomically on success.
    transactionOperations.executeWrite {
      queries.addAuthor("Author A", "a@example.com")
      queries.addAuthor("Author B", "b@example.com")
    }

    // Programmatic: explicit rollback without throwing an exception.
    // setRollbackOnly() marks the transaction for rollback — when the block returns, the framework rolls back instead
    // of committing.
    transactionOperations.executeWrite { status ->
      queries.addAuthor("Ghost", "ghost@example.com")
      status.setRollbackOnly()
    }

    // Declarative: @Transactional method — all queries share one transaction.
    createAuthorWithBooks("New Author", "new@example.com", listOf("Book 1", "Book 2"))

    // Nested transactions (savepoints): NESTED propagation creates a savepoint when a transaction is already active,
    // or starts a new transaction when none is. This lets code be context-independent — it doesn't need to know
    // whether the caller already started a transaction.
    val nested = TransactionDefinition.of(TransactionDefinition.Propagation.NESTED)
    transactionOperations.executeWrite {
      queries.addAuthor("Persisted", "p@example.com")

      // This nested block rolls back independently via its savepoint.
      runCatching {
        transactionOperations.execute(nested) {
          queries.addAuthor("Rolled Back", "rb@example.com")
          error("Trigger savepoint rollback")
        }
      }
      // "Persisted" survives, "Rolled Back" does not.
    }
  }

  /**
   * Declarative transactions: annotate a method with `@Transactional` and the framework manages the transaction
   * lifecycle. All Norm queries called within this method automatically participate in the same transaction.
   * If any query fails, the entire method rolls back.
   */
  @Transactional
  open fun createAuthorWithBooks(name: String, email: String, bookTitles: List<String>) {
    queries.addAuthor(name, email)
  }
}

data class AuthorContact(val name: String, val email: String?)
