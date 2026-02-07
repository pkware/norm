package norm.e2e.micronaut

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isPresent
import example.Author
import example.Book
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * E2E tests proving Norm-generated entities work with Micronaut Data JDBC.
 *
 * These tests verify:
 * - Norm-generated `@MappedEntity` classes are recognized by Micronaut Data
 * - KSP-generated repository implementations work correctly
 * - CRUD operations persist and retrieve data from a real PostgreSQL database
 */
@MicronautTest
class MicronautDataE2ETest {

  @Inject
  lateinit var authorRepository: AuthorRepository

  @Inject
  lateinit var bookRepository: BookRepository

  @Test
  fun `can persist and retrieve Norm-generated entity`() {
    // Create entity using Norm-generated @MappedEntity data class
    val author = Author(id = 0, name = "Jane Austen", email = "jane@example.com")

    // Persist via Micronaut Data repository (KSP-generated implementation)
    val saved = authorRepository.save(author)
    assertThat(saved.id).isNotNull()

    // Retrieve by ID
    val found = authorRepository.findById(saved.id)
    assertThat(found).isPresent()
    assertThat(found.get().name).isEqualTo("Jane Austen")
    assertThat(found.get().email).isEqualTo("jane@example.com")
  }

  @Test
  fun `derived query method works with Norm-generated entity`() {
    // Insert test data
    authorRepository.save(Author(id = 0, name = "Test Author", email = "test@example.com"))

    // Use derived query method - demonstrates Micronaut Data generates correct SQL
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
    // Save a single entity (using id=0 works for single saves because deleteAll() clears the table)
    val saved = authorRepository.save(Author(id = 0, name = "Author 1", email = "a1@test.com"))

    // Verify findAll() returns the saved entity
    // Note: We only test with one entity here because Micronaut Data requires @GeneratedValue
    // annotation for auto-increment columns when using non-nullable ID types. Without it,
    // multiple save() calls with id=0 cause duplicate key violations.
    val all = authorRepository.findAll().toList()
    assertThat(all).hasSize(1)
    assertThat(all[0].id).isEqualTo(saved.id)
    assertThat(all[0].name).isEqualTo("Author 1")
  }

  @Test
  fun `can update entity`() {
    // Create and save
    val author = authorRepository.save(Author(id = 0, name = "Original Name", email = "original@test.com"))

    // Update - data classes are immutable, so create new instance with same ID
    val updated = author.copy(name = "Updated Name")
    authorRepository.update(updated)

    // Verify update persisted
    val found = authorRepository.findById(author.id)
    assertThat(found.isPresent).isEqualTo(true)
    assertThat(found.get().name).isEqualTo("Updated Name")
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

  @Test
  fun `can query fields named using underscore`() {
    val author = authorRepository.save(Author(id = 0, name = "Charles Dickens", email = null))
    bookRepository.save(Book(0, "Oliver Twist", author.id))

    // Use a Micronaut Data JDBC method that gets a runtime proxy
    val books = bookRepository.findByAuthorId(author.id)
    assertThat(books).isNotEmpty()
  }
}
