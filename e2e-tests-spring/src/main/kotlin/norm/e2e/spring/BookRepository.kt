package norm.e2e.spring

import example.Book
import org.springframework.data.repository.CrudRepository

/**
 * Spring Data JDBC repository for [Book] entities.
 *
 * This repository uses the Norm-generated [Book] entity class
 * (with `@MappedEntity` annotation) to demonstrate that Norm-generated
 * code integrates with Spring Data's compile-time repository generation.
 */
interface BookRepository : CrudRepository<Book, Int> {

  fun findByAuthorId(authorId: Int): List<Book>
}
