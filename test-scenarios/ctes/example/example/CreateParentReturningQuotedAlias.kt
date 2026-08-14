package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH new_parent AS (
 *   INSERT INTO parent (name) VALUES (?) RETURNING id AS "parentId", description AS "parentDescription"
 * )
 * SELECT new_parent."parentId", new_parent."parentDescription" FROM new_parent
 * ```
 *
 * @property parentId (`parent.parentId`)
 * @property parentDescription (`parent.parentDescription`)
 */
@JvmRecord
public data class CreateParentReturningQuotedAlias(
  public val parentId: UUID,
  public val parentDescription: String?,
)
