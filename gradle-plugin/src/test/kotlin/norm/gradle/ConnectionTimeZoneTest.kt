package norm.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException
import java.util.TimeZone

/**
 * Verifies [openConnectionWithUtcSessionTimeZone] against a real postgres:18 server.
 *
 * These are not TestKit builds — they exercise the connect path directly so the JVM default zone can be
 * controlled deterministically. (Gradle does not propagate `-Duser.timezone` from `org.gradle.jvmargs`
 * to the daemon's default zone, so the failure cannot be reproduced through a TestKit build.)
 */
class ConnectionTimeZoneTest {

  private val originalDefaultZone: TimeZone = TimeZone.getDefault()

  @AfterEach
  fun restoreDefaultZone() {
    TimeZone.setDefault(originalDefaultZone)
  }

  @Test
  fun `pins the session zone to UTC so a legacy default alias does not break the connection`() {
    // Asia/Calcutta is a legacy alias postgres:18 Debian images no longer ship (relocated to the
    // uninstalled tzdata-legacy package).
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"))

    postgres18().use { container ->
      container.start()
      Class.forName("org.postgresql.Driver")

      // Causation control: the unguarded pgjdbc connect forwards the legacy alias and is rejected.
      val failure = runCatching {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).close()
      }.exceptionOrNull()
      assertThat(failure).isNotNull().isInstanceOf(SQLException::class)
      assertThat(failure!!.message).isNotNull().contains("""invalid value for parameter "TimeZone"""")

      // The guarded connect pins UTC and succeeds against the same server.
      openConnectionWithUtcSessionTimeZone(
        container.jdbcUrl,
        container.username,
        container.password,
      ).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeQuery("SHOW TimeZone").use { resultSet ->
            resultSet.next()
            assertThat(resultSet.getString(1)).isEqualTo("UTC")
          }
        }
      }

      // The caller's default zone is restored — no UTC leak into the shared daemon JVM.
      assertThat(TimeZone.getDefault().id).isEqualTo("Asia/Calcutta")
    }
  }

  private fun postgres18(): PostgreSQLContainer<*> {
    val imageName = DockerImageName.parse("postgres:18").asCompatibleSubstituteFor("postgres")
    return PostgreSQLContainer(imageName)
      // Run entirely in RAM — no disk I/O for the throwaway database.
      .withTmpFs(mapOf("/var/lib/postgresql" to "rw"))
  }
}
