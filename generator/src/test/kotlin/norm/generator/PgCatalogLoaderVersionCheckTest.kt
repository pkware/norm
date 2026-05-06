package norm.generator

import assertk.assertThat
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.DatabaseMetaData

@ExtendWith(MockitoExtension::class)
class PgCatalogLoaderVersionCheckTest {

  @Test
  fun `throws on PostgreSQL below minimum version`(@Mock connection: Connection, @Mock meta: DatabaseMetaData) {
    whenever(connection.metaData).thenReturn(meta)
    whenever(meta.databaseMajorVersion).thenReturn(15)

    val exception = assertThrows<IllegalStateException> {
      PgCatalogLoader(connection)
    }
    assertThat(exception.message!!.contains("16")).isTrue()
  }
}
