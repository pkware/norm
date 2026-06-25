package example

import jakarta.inject.Singleton
import kotlin.String
import norm.ColumnAdapter

@Singleton
public class EmailAddressAdapter : ColumnAdapter<EmailAddress, String> {
  override fun decode(databaseValue: String): EmailAddress = EmailAddress(databaseValue)

  override fun encode(`value`: EmailAddress): String = value.value
}
