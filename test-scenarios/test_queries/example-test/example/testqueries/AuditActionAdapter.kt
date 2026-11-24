package example.testqueries

import java.lang.IllegalArgumentException
import kotlin.String
import norm.ColumnAdapter

public class AuditActionAdapter : ColumnAdapter<AuditAction, String> {
  override fun decode(databaseValue: String): AuditAction = when (databaseValue) {
    "created" -> AuditAction.CREATED
    "updated" -> AuditAction.UPDATED
    "deleted" -> AuditAction.DELETED
    else -> throw IllegalArgumentException("Unknown AuditAction database value: " + databaseValue)
  }

  override fun encode(`value`: AuditAction): String = value.databaseValue
}
