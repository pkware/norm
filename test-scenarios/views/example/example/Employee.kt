package example

import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.jvm.JvmRecord

/**
 * A company employee.
 *
 * Maps to the `employee` table.
 *
 * @property id Unique identifier.
 * @property name Full name of the employee.
 * @property department Department the employee belongs to.
 * @property salary Annual salary in whole dollars.
 * @property active Whether the employee is currently active.
 */
@JvmRecord
public data class Employee(
  public val id: Int,
  public val name: String,
  public val department: String,
  public val salary: Int,
  public val active: Boolean,
)
