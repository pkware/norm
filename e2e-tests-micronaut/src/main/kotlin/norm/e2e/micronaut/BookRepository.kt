package norm.e2e.micronaut

import example.Book
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

/**
 * Micronaut Data JDBC repository for [Book] entities.
 *
 * This repository uses the Norm-generated [Book] entity class
 * (with `@MappedEntity` annotation) to demonstrate that Norm-generated
 * code integrates with Micronaut Data's compile-time repository generation.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
interface BookRepository : CrudRepository<Book, Int> {

  fun findByAuthorId(authorId: Int): List<Book>
}
