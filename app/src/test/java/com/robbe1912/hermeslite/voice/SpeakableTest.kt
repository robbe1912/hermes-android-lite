package com.robbe1912.hermeslite.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableTest {
    @Test
    fun dropsMarksAndKeepsWords() {
        val answer = """
            ## Result

            The job **ran** in `daily_inbox` and wrote [the report](https://example.invalid/r.md).
            - one
            > quoted

            ```text
            ok
            ```
        """.trimIndent()
        assertEquals(
            "Result\n\nThe job ran in daily_inbox and wrote the report.\none\nquoted\n\ntext\nok",
            speakable(answer),
        )
    }

    @Test
    fun keepsSignsInsideWords() {
        assertEquals("C# and a*b and file_name", speakable("C# and a*b and file_name"))
        assertEquals("_see_ a_b", speakable("_see_ [a_b](https://example.invalid/a_b_c)"))
    }
}
