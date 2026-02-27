package example

import kotlin.String
import norm.ColumnAdapter
import org.springframework.stereotype.Component

@Component
public class EmailAddressAdapter : ColumnAdapter<EmailAddress, String> {
  override fun decode(databaseValue: String): EmailAddress = EmailAddress(databaseValue)

  override fun encode(`value`: EmailAddress): String = value.value
}
