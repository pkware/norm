package norm.e2e.spring

import example.Author
import org.springframework.data.repository.CrudRepository

/**
 * Spring Data JDBC repository for [Author] entities.
 *
 * This repository uses the Norm-generated [Author] entity class
 * (with `@Table` annotation) to demonstrate that Norm-generated
 * code integrates with Spring Data's runtime proxy-based repositories.
 */
interface AuthorRepository : CrudRepository<Author, Int> {

  /**
   * Finds an author by their name.
   *
   * This derived query method demonstrates that Spring Data can
   * generate query implementations based on method names.
   */
  fun findByName(name: String): Author?
}
