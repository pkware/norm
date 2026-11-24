package example.testqueries

import kotlin.Any
import kotlin.Int
import kotlin.Long
import kotlin.String
import norm.Many
import norm.Query
import norm.Transactable

public interface TestQueries : Transactable {
  /**
   * Star projection of a table the main queries also project. Its type is generated once, in the main output.
   *
   * ```sql
   * SELECT * FROM author ORDER BY id
   * ```
   */
  public fun <T : Any> listAuthorsForAssertions(mapper: (id: Int, name: String) -> T): Many<T>

  /**
   * Star projection of a table the main queries also project. Its type is generated once, in the main output.
   *
   * ```sql
   * SELECT * FROM author ORDER BY id
   * ```
   */
  public fun listAuthorsForAssertions(): Many<Author> = listAuthorsForAssertions(::Author)

  public fun <T : Any> listAuthorsForAssertionsDynamically(mapper: (id: Int, name: String) -> T): Query<T>

  public fun listAuthorsForAssertionsDynamically(): Query<Author> = listAuthorsForAssertionsDynamically(::Author)

  /**
   * Star projection of a table only the test queries read, which pulls in the `audit_action` enum.
   *
   * ```sql
   * SELECT * FROM audit_log ORDER BY id
   * ```
   */
  public fun <T : Any> listAuditLog(mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Many<T>

  /**
   * Star projection of a table only the test queries read, which pulls in the `audit_action` enum.
   *
   * ```sql
   * SELECT * FROM audit_log ORDER BY id
   * ```
   */
  public fun listAuditLog(): Many<AuditLog> = listAuditLog(::AuditLog)

  public fun <T : Any> listAuditLogDynamically(mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Query<T>

  public fun listAuditLogDynamically(): Query<AuditLog> = listAuditLogDynamically(::AuditLog)

  /**
   * ```sql
   * SELECT * FROM audit_log WHERE action = ? ORDER BY id
   * ```
   */
  public fun <T : Any> listAuditLogByAction(action: AuditAction, mapper: (
    id: Int,
    author_id: Int,
    action: AuditAction,
  ) -> T): Many<T>

  /**
   * ```sql
   * SELECT * FROM audit_log WHERE action = ? ORDER BY id
   * ```
   */
  public fun listAuditLogByAction(action: AuditAction): Many<AuditLog> = listAuditLogByAction(action, ::AuditLog)

  /**
   * ```sql
   * SELECT author_id, count(*) AS entries FROM audit_log GROUP BY author_id ORDER BY author_id
   * ```
   */
  public fun <T : Any> countAuditLogByAuthor(mapper: (author_id: Int, entries: Long) -> T): Many<T>

  /**
   * ```sql
   * SELECT author_id, count(*) AS entries FROM audit_log GROUP BY author_id ORDER BY author_id
   * ```
   */
  public fun countAuditLogByAuthor(): Many<CountAuditLogByAuthor> = countAuditLogByAuthor(::CountAuditLogByAuthor)

  public fun <T : Any> countAuditLogByAuthorDynamically(mapper: (author_id: Int, entries: Long) -> T): Query<T>

  public fun countAuditLogByAuthorDynamically(): Query<CountAuditLogByAuthor> = countAuditLogByAuthorDynamically(::CountAuditLogByAuthor)
}
