package example

import java.util.UUID
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * WITH "He""llo" AS (
 *   SELECT id, UPPER(name) AS "My Col" FROM parent
 * )
 * SELECT id, "My Col" FROM "He""llo"
 * ```
 *
 * @property id (`parent.id`)
 * @property `My Col` (`UPPER(name)`)
 */
@JvmRecord
public data class SelectViaQuotedCteNameWithEmbeddedQuotes(
  public val id: UUID,
  public val `My Col`: String,
)
