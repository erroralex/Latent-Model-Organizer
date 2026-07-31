package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>The {@code ForgeUserMetadataWriterTest} suite validates the translation of Civitai
 * {@code trainedWords} and model descriptions into the A1111/Forge user-metadata sidecar
 * ({@code <basename>.json}) that the WebUI reads for its "Activation text" and "Description"
 * fields.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Format Fidelity:</b> Asserts the {@code "activation text"} key and the {@code ",, "}
 *   section separator understood by Forge and the Card Master extension.</li>
 *   <li><b>Description Sourcing:</b> Verifies the model description wins over the version note
 *   and that HTML is reduced to plain text.</li>
 *   <li><b>Non-Destructive Merge:</b> Ensures user-authored keys (notes, preferred weight)
 *   and any pre-existing field survive untouched.</li>
 *   <li><b>Per-Field Reporting:</b> Confirms the outcome distinguishes which fields were added.</li>
 *   <li><b>Graceful Degradation:</b> Verifies absent, empty, or malformed inputs are skipped
 *   without creating or corrupting files.</li>
 * </ul>
 * </p>
 */
class ForgeUserMetadataWriterTest {

    private ForgeUserMetadataWriter writer;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new ForgeUserMetadataWriter();
        objectMapper = new ObjectMapper();
    }

    private JsonNode response(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private Path modelPath() {
        return tempDir.resolve("my_lora.safetensors");
    }

    private Path metadataPath() {
        return tempDir.resolve("my_lora.json");
    }

    private JsonNode readMetadata() throws Exception {
        return objectMapper.readTree(metadataPath().toFile());
    }

    @Test
    void shouldCreateMetadataFileWithActivationText() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertTrue(written);
        assertTrue(Files.exists(metadataPath()));
        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldJoinMultipleTrainedWordsWithSectionSeparator() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"outfit1, long hair\", \"outfit2, ponytail\"]}");

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertEquals("outfit1, long hair,, outfit2, ponytail",
                readMetadata().get("activation text").asText());
    }

    @Test
    void shouldPreserveExistingUserAuthoredKeys() throws Exception {
        Files.writeString(metadataPath(), """
                {
                  "description": "my favourite lora",
                  "preferred weight": 0.75,
                  "notes": "works best at 30 steps"
                }
                """);
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertTrue(written);
        JsonNode result = readMetadata();
        assertEquals("ohwx style", result.get("activation text").asText());
        assertEquals("my favourite lora", result.get("description").asText());
        assertEquals(0.75, result.get("preferred weight").asDouble());
        assertEquals("works best at 30 steps", result.get("notes").asText());
    }

    @Test
    void shouldNotOverwriteExistingActivationText() throws Exception {
        Files.writeString(metadataPath(), "{\"activation text\": \"my own trigger\"}");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertFalse(written);
        assertEquals("my own trigger", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldFillBlankExistingActivationText() throws Exception {
        Files.writeString(metadataPath(), "{\"activation text\": \"   \", \"notes\": \"keep me\"}");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertTrue(written);
        JsonNode result = readMetadata();
        assertEquals("ohwx style", result.get("activation text").asText());
        assertEquals("keep me", result.get("notes").asText());
    }

    /**
     * Civitai authors routinely leave a trailing comma on each trained word. Joining those
     * verbatim yields {@code ",,,"} runs, which shift the section boundaries that Forge
     * extensions split on.
     */
    @Test
    void shouldNotEmitCommaRunsWhenTrainedWordsAreCommaTerminated() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"sidelighting, \", \"backlighting,\", \", moonlight\"]}");

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        String activationText = readMetadata().get("activation text").asText();
        assertEquals("sidelighting,, backlighting,, moonlight", activationText);
        assertFalse(activationText.contains(",,,"), "Section separators must not run together");
    }

    @Test
    void shouldSkipTrainedWordsThatAreOnlyPunctuation() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\",\", \" , \", \"ohwx style\"]}");

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldSkipWhenTrainedWordsIsAbsent() throws Exception {
        JsonNode node = response("{\"baseModel\": \"SDXL 1.0\"}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertFalse(written);
        assertFalse(Files.exists(metadataPath()), "No file should be created when there is nothing to write");
    }

    @Test
    void shouldSkipWhenTrainedWordsIsEmpty() throws Exception {
        JsonNode node = response("{\"trainedWords\": []}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertFalse(written);
        assertFalse(Files.exists(metadataPath()));
    }

    @Test
    void shouldIgnoreBlankAndNonTextualTrainedWords() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"  \", \"ohwx style\", null, 42, \"\"]}");

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldWriteModelDescriptionAsPlainText() throws Exception {
        JsonNode node = response("""
                {"model": {"description": "<p><strong>Use at 0.8.</strong></p><p>Trained on 40 images.</p>"}}
                """);

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertTrue(outcome.descriptionWritten());
        assertFalse(outcome.activationTextWritten());
        assertEquals("Use at 0.8.\nTrained on 40 images.", readMetadata().get("description").asText());
    }

    @Test
    void shouldPreferModelDescriptionOverVersionNote() throws Exception {
        JsonNode node = response("""
                {"description": "<p>v2 changelog</p>", "model": {"description": "<p>The real description</p>"}}
                """);

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertEquals("The real description", readMetadata().get("description").asText());
    }

    @Test
    void shouldFallBackToVersionNoteWhenModelHasNoDescription() throws Exception {
        JsonNode node = response("""
                {"description": "<p>v2 changelog</p>", "model": {"name": "Some LoRA"}}
                """);

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertEquals("v2 changelog", readMetadata().get("description").asText());
    }

    @Test
    void shouldTreatHtmlThatReducesToNothingAsNoDescription() throws Exception {
        JsonNode node = response("{\"model\": {\"description\": \"<p></p><br>\"}}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertFalse(outcome.wroteAnything());
        assertFalse(Files.exists(metadataPath()));
    }

    @Test
    void shouldNotOverwriteExistingDescription() throws Exception {
        Files.writeString(metadataPath(), "{\"description\": \"my own words\"}");
        JsonNode node = response("{\"model\": {\"description\": \"<p>Civitai copy</p>\"}}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertFalse(outcome.descriptionWritten());
        assertEquals("my own words", readMetadata().get("description").asText());
    }

    @Test
    void shouldReportBothFieldsWhenBothAreAdded() throws Exception {
        JsonNode node = response("""
                {"trainedWords": ["ohwx style"], "model": {"description": "<p>Nice LoRA</p>"}}
                """);

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertTrue(outcome.activationTextWritten());
        assertTrue(outcome.descriptionWritten());
        assertTrue(outcome.wroteAnything());
        JsonNode result = readMetadata();
        assertEquals("ohwx style", result.get("activation text").asText());
        assertEquals("Nice LoRA", result.get("description").asText());
    }

    /** One field being already set must not block the other from being filled. */
    @Test
    void shouldFillDescriptionWhenActivationTextIsAlreadySet() throws Exception {
        Files.writeString(metadataPath(), "{\"activation text\": \"my own trigger\"}");
        JsonNode node = response("""
                {"trainedWords": ["ohwx style"], "model": {"description": "<p>Nice LoRA</p>"}}
                """);

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");

        assertFalse(outcome.activationTextWritten());
        assertTrue(outcome.descriptionWritten());
        JsonNode result = readMetadata();
        assertEquals("my own trigger", result.get("activation text").asText());
        assertEquals("Nice LoRA", result.get("description").asText());
    }

    @Test
    void shouldWriteNothingOnDryRunButReportBothFields() throws Exception {
        JsonNode node = response("""
                {"trainedWords": ["ohwx style"], "model": {"description": "<p>Nice LoRA</p>"}}
                """);

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora", true);

        assertTrue(outcome.activationTextWritten());
        assertTrue(outcome.descriptionWritten());
        assertFalse(Files.exists(metadataPath()), "Dry run must not write");
    }

    @Test
    void shouldLeaveUnparseableMetadataUntouched() throws Exception {
        Files.writeString(metadataPath(), "{ this is not valid json");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertFalse(written);
        assertEquals("{ this is not valid json", Files.readString(metadataPath()));
    }

    @Test
    void shouldLeaveNonObjectMetadataUntouched() throws Exception {
        Files.writeString(metadataPath(), "[\"unexpected array\"]");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        var outcome = writer.writeUserMetadata(node, modelPath(), "my_lora");
        boolean written = outcome.activationTextWritten();

        assertFalse(written);
        assertEquals("[\"unexpected array\"]", Files.readString(metadataPath()));
    }

    @Test
    void shouldNotLeaveTemporaryFilesBehind() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        writer.writeUserMetadata(node, modelPath(), "my_lora");

        try (var entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "Temporary write file should have been moved into place");
        }
    }
}
