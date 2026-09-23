package norm.generator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CteScopeStackTest {

  @Nested
  inner class FrameAtSweep {

    @Test
    fun `frameAt returns the single constructed frame at index 0`() {
      val stack = CteScopeStack("f0")

      assertThat(stack.frameAt(0)).isEqualTo("f0")
    }

    @Test
    fun `frameAt returns null one past the only frame`() {
      val stack = CteScopeStack("f0")

      assertThat(stack.frameAt(1)).isNull()
    }

    @Test
    fun `frameAt returns null for a negative levelsUp`() {
      val stack = CteScopeStack("f0")

      assertThat(stack.frameAt(-1)).isNull()
    }
  }

  @Nested
  inner class EnteringSweep {

    private val threeFrameStack = CteScopeStack("f2").entering(0, "f1").entering(0, "f0")

    @Test
    fun `entering at levelsUp 1 drops the shadowed frame and pushes the new own scope on front`() {
      val stack = threeFrameStack.entering(1, "own")

      assertThat(stack.frameAt(0)).isEqualTo("own")
      assertThat(stack.frameAt(1)).isEqualTo("f1")
      assertThat(stack.frameAt(2)).isEqualTo("f2")
      assertThat(stack.frameAt(3)).isNull()
    }

    @Test
    fun `entering at a levelsUp equal to the frame count throws`() {
      assertThrows<IllegalArgumentException> { threeFrameStack.entering(3, "own") }
    }

    @Test
    fun `entering at a negative levelsUp throws`() {
      assertThrows<IllegalArgumentException> { threeFrameStack.entering(-1, "own") }
    }
  }
}
