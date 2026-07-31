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
 * {@code trainedWords} into the A1111/Forge user-metadata sidecar ({@code <basename>.json})
 * that the WebUI reads for its "Activation text" field.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Format Fidelity:</b> Asserts the {@code "activation text"} key and the {@code ",, "}
 *   section separator understood by Forge and the Card Master extension.</li>
 *   <li><b>Non-Destructive Merge:</b> Ensures user-authored keys (notes, preferred weight)
 *   and any pre-existing activation text survive untouched.</li>
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

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertTrue(written);
        assertTrue(Files.exists(metadataPath()));
        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldJoinMultipleTrainedWordsWithSectionSeparator() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"outfit1, long hair\", \"outfit2, ponytail\"]}");

        writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

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

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

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

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertFalse(written);
        assertEquals("my own trigger", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldFillBlankExistingActivationText() throws Exception {
        Files.writeString(metadataPath(), "{\"activation text\": \"   \", \"notes\": \"keep me\"}");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

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

        writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        String activationText = readMetadata().get("activation text").asText();
        assertEquals("sidelighting,, backlighting,, moonlight", activationText);
        assertFalse(activationText.contains(",,,"), "Section separators must not run together");
    }

    @Test
    void shouldSkipTrainedWordsThatAreOnlyPunctuation() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\",\", \" , \", \"ohwx style\"]}");

        writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldSkipWhenTrainedWordsIsAbsent() throws Exception {
        JsonNode node = response("{\"baseModel\": \"SDXL 1.0\"}");

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertFalse(written);
        assertFalse(Files.exists(metadataPath()), "No file should be created when there is nothing to write");
    }

    @Test
    void shouldSkipWhenTrainedWordsIsEmpty() throws Exception {
        JsonNode node = response("{\"trainedWords\": []}");

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertFalse(written);
        assertFalse(Files.exists(metadataPath()));
    }

    @Test
    void shouldIgnoreBlankAndNonTextualTrainedWords() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"  \", \"ohwx style\", null, 42, \"\"]}");

        writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertEquals("ohwx style", readMetadata().get("activation text").asText());
    }

    @Test
    void shouldLeaveUnparseableMetadataUntouched() throws Exception {
        Files.writeString(metadataPath(), "{ this is not valid json");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertFalse(written);
        assertEquals("{ this is not valid json", Files.readString(metadataPath()));
    }

    @Test
    void shouldLeaveNonObjectMetadataUntouched() throws Exception {
        Files.writeString(metadataPath(), "[\"unexpected array\"]");
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        boolean written = writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        assertFalse(written);
        assertEquals("[\"unexpected array\"]", Files.readString(metadataPath()));
    }

    @Test
    void shouldNotLeaveTemporaryFilesBehind() throws Exception {
        JsonNode node = response("{\"trainedWords\": [\"ohwx style\"]}");

        writer.writeActivationTextIfAbsent(node, modelPath(), "my_lora");

        try (var entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "Temporary write file should have been moved into place");
        }
    }
}
