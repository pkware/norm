package example

import java.util.UUID
import kotlin.Any
import kotlin.Long
import kotlin.String
import norm.Many
import norm.Transactable

public interface Queries : Transactable {
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
   */
  public fun <T : Any> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
  ): Many<T>

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
   */
  public fun findClaimingRuns(deploymentId: UUID, runLimit: Long): Many<FindClaimingRuns> = findClaimingRuns(deploymentId, runLimit, ::FindClaimingRuns)
}
