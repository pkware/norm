package norm.generator.libpgquery

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RegressSweepTest {

  @Nested
  inner class Statements {

    @ParameterizedTest
    @ValueSource(strings = ["16", "17"])
    fun `every statement parses unless it is skipped or on the reject list`(major: String) {
      val rejected = RejectList.load().filter { it.major == major }.associateBy { it.statementKey }
      val unexplained = mutableListOf<String>()
      val rejectedThatParse = mutableListOf<String>()
      val seenRejected = mutableSetOf<String>()

      for (file in PgRegress.files(major)) {
        for (statement in file.statements) {
          val key = "$major/${file.name}#${statement.ordinal}"
          val entry = rejected[key]
          if (statement.skip != null) {
            if (entry != null) rejectedThatParse += "$key is skipped, so it cannot be on the reject list"
            continue
          }
          val failure = if (statement.isTokenizerRejected) {
            "the lexer rejects it"
          } else {
            try {
              LibPgQuery.parse(statement.text)
              null
            } catch (exception: LibPgQueryException) {
              exception.message
            }
          }
          when {
            failure == null && entry != null -> rejectedThatParse += "$key parses but is on the reject list"
            failure != null && entry == null -> unexplained += "$key: $failure: ${singleLine(statement.firstLine)}"
            entry != null -> {
              seenRejected += key
              if (singleLine(statement.firstLine) != entry.firstLine) {
                rejectedThatParse += "$key starts with '${singleLine(statement.firstLine)}', not '${entry.firstLine}'"
              }
            }
          }
        }
      }

      assertThat(unexplained, "statements that fail without a reject list entry").isEmpty()
      assertThat(rejectedThatParse, "reject list entries that do not match their statement").isEmpty()
      assertThat(rejected.keys - seenRejected, "reject list entries without a statement").isEmpty()
    }
  }

  @Nested
  inner class Rejects {

    @Test
    fun `every entry cites an error that follows its statement in the expected output`() {
      val firstLines = PgRegress.majors.associateWith { major ->
        PgRegress.files(major).associate { it.name to it.statements.map(RegressStatement::firstLine) }
      }
      val unjustified = RejectList.load().filterNot { entry ->
        val lines = checkNotNull(PgRegress.expectedOutput(entry.major, entry.outputFile)) {
          "${entry.major}/expected/${entry.outputFile} is not vendored"
        }
        val next = firstLines.getValue(entry.major).getValue(entry.file).getOrNull(entry.ordinal)
        entry.isJustified(lines, next)
      }
      assertThat(unjustified.map { it.statementKey }).isEmpty()
    }
  }

  @Nested
  inner class Skips {

    @Test
    fun `the committed skip list matches the skips the sweep computes`() {
      val committed = checkNotNull(RegressSweepTest::class.java.getResourceAsStream("/pg-regress/sweep-skips.txt")) {
        "pg-regress/sweep-skips.txt is missing. Run ./gradlew :generator:generateScannerBaseline."
      }.use { String(it.readBytes(), Charsets.UTF_8) }.replace("\r\n", "\n")

      assertThat(SweepSkipsReport.render()).isEqualTo(committed)
    }
  }
}
