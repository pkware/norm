package example

import java.util.UUID
import norm.ColumnAdapter

public class ExternalReferenceAdapter : ColumnAdapter<ExternalReference, UUID> {
  override fun decode(databaseValue: UUID): ExternalReference = ExternalReference(databaseValue)

  override fun encode(`value`: ExternalReference): UUID = value.value
}
