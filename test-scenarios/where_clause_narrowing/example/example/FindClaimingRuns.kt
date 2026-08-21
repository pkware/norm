package example

import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * ```sql
 * SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
 * FROM prefix_enumeration_backlog
 * WHERE deployment_id = ?
 *   AND claimed
 *   AND completed_at IS NULL
 *   AND claimed_by_workflow_id IS NOT NULL
 *   AND claimed_by_run_id IS NOT NULL
 * LIMIT ?
 * ```
 *
 * @property claimed_by_workflow_id (`prefix_enumeration_backlog.claimed_by_workflow_id`)
 * @property claimed_by_run_id (`prefix_enumeration_backlog.claimed_by_run_id`)
 */
@JvmRecord
public data class FindClaimingRuns(
  public val claimed_by_workflow_id: String,
  public val claimed_by_run_id: String,
)
