package example

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.Any
import kotlin.Long
import kotlin.String
import kotlin.Unit
import norm.ConnectionProvider
import norm.Many
import norm.ManyProcessor
import norm.NormDriver
import norm.RealTransactable

public class PostgresQueries(
  connectionProvider: ConnectionProvider,
) : RealTransactable(connectionProvider),
    Queries {
  private val driver: NormDriver = NormDriver(connectionProvider)

  private fun <T : Any, Return> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
    processor: ManyProcessor<T, Return>,
  ): Return {
    val sql = """
        |SELECT DISTINCT claimed_by_workflow_id, claimed_by_run_id
        |FROM prefix_enumeration_backlog
        |WHERE deployment_id = ?
        |  AND claimed
        |  AND completed_at IS NULL
        |  AND claimed_by_workflow_id IS NOT NULL
        |  AND claimed_by_run_id IS NOT NULL
        |LIMIT ?
        """.trimMargin()
    val rowReader: ResultSet.() -> T = {
      mapper(
        getString(1),
        getString(2),
      )
    }
    val queryBinder: (PreparedStatement.() -> Unit)? = {
      setObject(1, deploymentId)
      setLong(2, runLimit)
    }
    return processor.invoke(sql, rowReader, queryBinder)
  }

  override fun <T : Any> findClaimingRuns(
    deploymentId: UUID,
    runLimit: Long,
    mapper: (claimed_by_workflow_id: String, claimed_by_run_id: String) -> T,
  ): Many<T> = findClaimingRuns(deploymentId, runLimit, mapper, driver::queryMany)
}
