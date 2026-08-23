package norm.generator

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * The PostgreSQL major version under test, selectable via `-Dnorm.test.pgVersion=<16|17|18>`. Defaults to `18`.
 */
internal val pgVersion: String = System.getProperty("norm.test.pgVersion", "18")

/**
 * Builds a [PostgreSQLContainer] pinned to [pgVersion]'s Alpine image, waiting for both the "ready to accept
 * connections" log line and the listening port before startup is considered complete.
 *
 * When [inMemory] is `true`, the container is mounted on a tmpfs and started with durability features disabled.
 * This is safe for a throwaway test database and meaningfully speeds up suites that create/drop many schemas.
 */
internal fun testPostgresContainer(databaseName: String, inMemory: Boolean = false): PostgreSQLContainer<*> =
  PostgreSQLContainer(
    DockerImageName.parse("postgres:$pgVersion-alpine").asCompatibleSubstituteFor("postgres"),
  ).apply {
    withDatabaseName(databaseName)
    waitingFor(
      WaitAllStrategy()
        .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
        .withStrategy(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(60)),
    )
    if (inMemory) {
      // Run entirely in RAM — no disk I/O for the throwaway database.
      // PostgreSQL 18+ stores data in a version-specific subdirectory under /var/lib/postgresql,
      // so the mount must be at the parent rather than /var/lib/postgresql/data.
      withTmpFs(mapOf("/var/lib/postgresql" to "rw"))
      // Disable durability features we don't need in a throwaway container.
      withCommand(
        "postgres",
        "-c", "fsync=off",
        "-c", "full_page_writes=off",
        "-c", "synchronous_commit=off",
        "-c", "max_wal_senders=0",
        "-c", "wal_level=minimal",
      )
    }
  }
