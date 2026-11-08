package au.net.maz.privacyai

import au.net.maz.privacyai.comfy.WorkflowPatcher
import au.net.maz.privacyai.data.model.Character
import au.net.maz.privacyai.data.model.Roleplay
import au.net.maz.privacyai.llm.Prompts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    // Ref 2 -> ImageScale -> Encoder.image2 (optional); Ref 1 -> Encoder.image1; Encoder -> Save.
    private val graph = Json.parseToJsonElement(
        """
        {
          "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}, "_meta": {"title": "Ref 2"}},
          "2": {"class_type": "ImageScale", "inputs": {"image": ["1", 0], "width": 512}, "_meta": {"title": "Scale"}},
          "3": {"class_type": "Encoder", "inputs": {"image1": ["4", 0], "image2": ["2", 0], "clip": "x"}, "_meta": {"title": "Enc"}},
          "4": {"class_type": "LoadImage", "inputs": {"image": "b.png"}, "_meta": {"title": "Ref 1"}},
          "5": {"class_type": "SaveImage", "inputs": {"images": ["3", 0]}, "_meta": {"title": "Out"}}
        }
        """,
    ).jsonObject

    private val required = mapOf(
        "ImageScale" to setOf("image", "width"),
        "Encoder" to setOf("clip"),
        "SaveImage" to setOf("images"),
        "LoadImage" to setOf("image"),
    )

    @Test
    fun emptyOptionalSlotIsRemovedWithItsChain() = runBlocking {
        val out = WorkflowPatcher.pruneEmptySlots(graph, listOf("Ref 2"), "5") { required[it] }.getOrThrow()
        assertEquals(setOf("3", "4", "5"), out.keys)
        val enc = out["3"]!!.jsonObject["inputs"]!!.jsonObject
        assertFalse("image2" in enc)
        assertTrue("image1" in enc)
    }

    @Test
    fun requiredSlotThatReachesOutputFails() = runBlocking {
        val strict = required + ("Encoder" to setOf("clip", "image2"))
        val r = WorkflowPatcher.pruneEmptySlots(graph, listOf("Ref 2"), "5") { strict[it] }
        assertTrue(r.isFailure)
    }

    @Test
    fun unknownNodeTypesTreatInputsAsOptional() = runBlocking {
        val out = WorkflowPatcher.pruneEmptySlots(graph, listOf("Ref 1"), "5") { null }.getOrThrow()
        assertEquals(setOf("1", "2", "3", "5"), out.keys)
    }

    private val rp = Roleplay(
        yourName = "Mark",
        characters = listOf(
            Character(id = "a", name = "Mara"),
            Character(id = "b", name = "Mara Jensen"),
        ),
    )

    @Test
    fun speakerPrefixPicksTheLongestMatchingName() {
        assertEquals("b" to "Hello.", Prompts.parseSpeaker(rp, "Mara Jensen: Hello.", null))
        assertEquals("a" to "Hi.", Prompts.parseSpeaker(rp, "Mara: Hi.", null))
    }

    @Test
    fun noPrefixFallsBackToForcedSpeaker() {
        assertEquals("b" to "Hmm.", Prompts.parseSpeaker(rp, "Hmm.", "b"))
        assertEquals("a" to "Hmm.", Prompts.parseSpeaker(rp, "Hmm.", null))
    }

    @Test
    fun reasoningBeforeThePrefixIsKept() {
        val (speaker, text) = Prompts.parseSpeaker(rp, "<think>plan</think>Mara: Yes.", null)
        assertEquals("a", speaker)
        assertEquals("<think>plan</think>Yes.", text)
    }

    @Test
    fun messageVersions() {
        val m = au.net.maz.privacyai.data.model.Message(speaker = "a", text = "one")
        assertEquals(listOf("one"), m.versions)
        val two = m.newVersion().withText("two")
        assertEquals(listOf("one", "two"), two.versions)
        assertEquals("two", two.text)
        val back = two.showVersion(0)
        assertEquals("one", back.text)
        assertEquals(listOf("one (edited)", "two"), back.withText("one (edited)").versions)
    }
}
