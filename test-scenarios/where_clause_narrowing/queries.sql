-- name: findClaimingRuns :many
SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
FROM prefix_enumeration_backlog
WHERE deployment_id = :deploymentId
  AND claimed
  AND completed_at IS NULL
  AND claimed_by_workflow_id IS NOT NULL
  AND claimed_by_run_id IS NOT NULL
LIMIT :runLimit;
