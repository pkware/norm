package norm.e2e.micronaut

import example.Author
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

/**
 * Micronaut Data JDBC repository for [Author] entities.
 *
 * This repository uses the Norm-generated [Author] entity class
 * (with `@MappedEntity` annotation) to demonstrate that Norm-generated
 * code integrates with Micronaut Data's compile-time repository generation.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
interface AuthorRepository : CrudRepository<Author, Int> {

  /**
   * Finds an author by their name.
   *
   * This derived query method demonstrates that Micronaut Data can
   * generate query implementations based on method names.
   */
  fun findByName(name: String): Author?
}
