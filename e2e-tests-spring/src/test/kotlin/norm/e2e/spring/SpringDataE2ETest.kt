package norm.e2e.spring

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isPresent
import example.Author
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * E2E tests proving Norm-generated entities work with Spring Data JDBC.
 *
 * These tests verify:
 * - Norm-generated `@Table` classes are recognized by Spring Data
 * - Runtime proxy-based repository implementations work correctly
 * - CRUD operations persist and retrieve data from a real PostgreSQL database
 */
@SpringBootTest
@ActiveProfiles("test")
class SpringDataE2ETest {

  @Autowired
  lateinit var authorRepository: AuthorRepository

  @BeforeEach
  fun setup() {
    authorRepository.deleteAll()
  }

  @Test
  fun `can persist and retrieve Norm-generated entity`() {
    // Create entity using Norm-generated @Table data class
    val author = Author(id = 0, name = "Charles Dickens", email = "charles@example.com")

    // Persist via Spring Data repository (proxy-based implementation)
    val saved = authorRepository.save(author)
    assertThat(saved.id).isNotNull()

    // Retrieve by ID
    val found = authorRepository.findById(saved.id)
    assertThat(found).isPresent()
    assertThat(found.get().name).isEqualTo("Charles Dickens")
    assertThat(found.get().email).isEqualTo("charles@example.com")
  }

  @Test
  fun `derived query method works with Norm-generated entity`() {
    // Insert test data
    authorRepository.save(Author(id = 0, name = "Test Author", email = "test@example.com"))

    // Use derived query method - demonstrates Spring Data generates correct SQL
    val found = authorRepository.findByName("Test Author")
    assertThat(found).isNotNull()
    assertThat(found!!.email).isEqualTo("test@example.com")
  }

  @Test
  fun `nullable email column is handled correctly`() {
    // Create entity with null email
    val author = Author(id = 0, name = "No Email", email = null)
    val saved = authorRepository.save(author)

    // Retrieve and verify null is preserved
    val found = authorRepository.findById(saved.id)
    assertThat(found.isPresent).isEqualTo(true)
    assertThat(found.get().email).isNull()
  }

  @Test
  fun `can retrieve all entities`() {
    // Insert multiple authors
    authorRepository.save(Author(id = 0, name = "Author 1", email = "a1@test.com"))
    authorRepository.save(Author(id = 0, name = "Author 2", email = "a2@test.com"))
    authorRepository.save(Author(id = 0, name = "Author 3", email = null))

    // Retrieve all
    val all = authorRepository.findAll().toList()
    assertThat(all).hasSize(3)
  }

  @Test
  fun `can count entities`() {
    authorRepository.save(Author(id = 0, name = "A1", email = null))
    authorRepository.save(Author(id = 0, name = "A2", email = null))

    assertThat(authorRepository.count()).isEqualTo(2)
  }

  @Test
  fun `can delete entity`() {
    // Create and save
    val author = authorRepository.save(Author(id = 0, name = "To Delete", email = null))

    // Delete
    authorRepository.delete(author)

    // Verify deleted
    val found = authorRepository.findById(author.id)
    assertThat(found.isPresent).isEqualTo(false)
  }
}
