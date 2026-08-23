package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonValueTest {

  @Nested
  inner class Scalars {

    @Test
    fun `parses a bare string`() {
      assertThat(JsonValue.parse("\"hello\"")).isEqualTo(JsonValue.JsonString("hello"))
    }

    @Test
    fun `parses a number as a scalar`() {
      assertThat(JsonValue.parse("42.5")).isEqualTo(JsonValue.JsonScalar("42.5"))
    }

    @Test
    fun `parses true as a scalar`() {
      assertThat(JsonValue.parse("true")).isEqualTo(JsonValue.JsonScalar("true"))
    }

    @Test
    fun `parses null as a scalar`() {
      assertThat(JsonValue.parse("null")).isEqualTo(JsonValue.JsonScalar("null"))
    }
  }

  @Nested
  inner class Strings {

    @Test
    fun `unescapes a quote inside a string`() {
      assertThat(JsonValue.parse("\"a\\\"b\"")).isEqualTo(JsonValue.JsonString("a\"b"))
    }

    @Test
    fun `unescapes a backslash inside a string`() {
      assertThat(JsonValue.parse("\"a\\\\b\"")).isEqualTo(JsonValue.JsonString("a\\b"))
    }

    @Test
    fun `unescapes a unicode sequence`() {
      assertThat(JsonValue.parse("\"\\u0041\"")).isEqualTo(JsonValue.JsonString("A"))
    }

    @Test
    fun `unescapes newline and tab`() {
      assertThat(JsonValue.parse("\"a\\nb\\tc\"")).isEqualTo(JsonValue.JsonString("a\nb\tc"))
    }
  }

  @Nested
  inner class Containers {

    @Test
    fun `parses an empty object`() {
      assertThat(JsonValue.parse("{}")).isEqualTo(JsonValue.JsonObject(emptyMap()))
    }

    @Test
    fun `parses an empty array`() {
      assertThat(JsonValue.parse("[]")).isEqualTo(JsonValue.JsonArray(emptyList()))
    }

    @Test
    fun `parses an object with multiple fields`() {
      val parsed = JsonValue.parse("""{"a": "x", "b": 1}""")
      assertThat(parsed).isEqualTo(
        JsonValue.JsonObject(mapOf("a" to JsonValue.JsonString("x"), "b" to JsonValue.JsonScalar("1"))),
      )
    }

    @Test
    fun `parses nested objects and arrays, matching EXPLAIN FORMAT JSON shape`() {
      val explainShapedJson = """
        [
          {
            "Plan": {
              "Node Type": "ModifyTable",
              "Operation": "Merge",
              "Plans": [
                {
                  "Node Type": "Hash Join",
                  "Join Type": "Left",
                  "Plans": [
                    {"Node Type": "Seq Scan", "Relation Name": "src", "Alias": "src"},
                    {
                      "Node Type": "Hash",
                      "Plans": [
                        {"Node Type": "Seq Scan", "Relation Name": "tgt", "Alias": "tgt"}
                      ]
                    }
                  ]
                }
              ]
            }
          }
        ]
      """.trimIndent()
      val parsed = JsonValue.parse(explainShapedJson) as JsonValue.JsonArray
      val plan = (parsed.items.single() as JsonValue.JsonObject).fields["Plan"] as JsonValue.JsonObject
      assertThat((plan.fields["Node Type"] as JsonValue.JsonString).value).isEqualTo("ModifyTable")
      val joinNode = (plan.fields["Plans"] as JsonValue.JsonArray).items.single() as JsonValue.JsonObject
      assertThat((joinNode.fields["Join Type"] as JsonValue.JsonString).value).isEqualTo("Left")
    }

    @Test
    fun `a string value containing brace and bracket characters does not confuse nesting`() {
      val parsed = JsonValue.parse("""{"Hash Cond": "(src.id = tgt.id)", "Filter": "a[1] = b{2}"}""")
      val obj = parsed as JsonValue.JsonObject
      assertThat((obj.fields["Hash Cond"] as JsonValue.JsonString).value).isEqualTo("(src.id = tgt.id)")
      assertThat((obj.fields["Filter"] as JsonValue.JsonString).value).isEqualTo("a[1] = b{2}")
    }
  }

  @Nested
  inner class MalformedInput {

    @Test
    fun `throws on unterminated object`() {
      assertThrows<IllegalArgumentException> { JsonValue.parse("""{"a": 1""") }
    }

    @Test
    fun `throws on unterminated string`() {
      assertThrows<IllegalArgumentException> { JsonValue.parse("\"abc") }
    }

    @Test
    fun `throws on trailing garbage after a valid value`() {
      assertThrows<IllegalArgumentException> { JsonValue.parse("""{"a": 1} garbage""") }
    }
  }
}
