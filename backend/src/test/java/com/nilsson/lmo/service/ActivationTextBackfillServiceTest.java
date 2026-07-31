package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.domain.OperationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>The {@code ActivationTextBackfillServiceTest} suite validates the offline pass that
 * converts previously downloaded {@code .civitai.info} sidecars into the
 * {@code <basename>.json} user-metadata files read by Forge.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Scan Scope:</b> Asserts recursive and shallow directory traversal.</li>
 *   <li><b>Idempotence:</b> Ensures a second run over the same library writes nothing new.</li>
 *   <li><b>Accounting:</b> Verifies each skip reason is reported distinctly, and that dry runs
 *   report the same decision without touching disk.</li>
 *   <li><b>Resilience:</b> Confirms malformed sidecars are recorded as errors without aborting
 *   the scan, and that cancellation is honoured.</li>
 * </ul>
 * </p>
 */
class ActivationTextBackfillServiceTest {

    private ActivationTextBackfillService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ActivationTextBackfillService(new ForgeUserMetadataWriter(), new ObjectMapper());
    }

    /** Creates a model file plus a Civitai sidecar carrying the given trained words. */
    private void givenModel(Path dir, String baseName, String... trainedWords) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(baseName + ".safetensors"), "fake weights");

        StringBuilder words = new StringBuilder();
        for (int i = 0; i < trainedWords.length; i++) {
            if (i > 0) words.append(", ");
            words.append('"').append(trainedWords[i]).append('"');
        }
        Files.writeString(dir.resolve(baseName + ".civitai.info"),
                "{\"baseModel\": \"SDXL 1.0\", \"trainedWords\": [" + words + "]}");
    }

    private OperationReport backfill(boolean isRecursive, boolean isDryRun) {
        return service.backfillActivationText(tempDir, isRecursive, isDryRun,
                () -> false, total -> {}, () -> {});
    }

    private String activationTextOf(String baseName) throws IOException {
        return new ObjectMapper()
                .readTree(tempDir.resolve(baseName + ".json").toFile())
                .path("activation text").asText();
    }

    @Test
    void shouldWriteActivationTextFromExistingSidecar() throws Exception {
        givenModel(tempDir, "my_lora", "ohwx style");

        OperationReport report = backfill(false, false);

        assertEquals("ohwx style", activationTextOf("my_lora"));
        assertEquals(1, report.summary().get("Trigger Words Saved"));
        assertEquals(1, report.totalProcessed());
        assertTrue(report.errors().isEmpty());
    }

    @Test
    void shouldBeIdempotentOnSecondRun() throws Exception {
        givenModel(tempDir, "my_lora", "ohwx style");

        backfill(false, false);
        OperationReport second = backfill(false, false);

        assertNull(second.summary().get("Trigger Words Saved"));
        assertEquals(1, second.summary().get("Skipped (already set)"));
        assertEquals("ohwx style", activationTextOf("my_lora"));
    }

    @Test
    void shouldSkipSidecarWithoutTrainedWords() throws Exception {
        givenModel(tempDir, "plain_checkpoint");

        OperationReport report = backfill(false, false);

        assertEquals(1, report.summary().get("Skipped (no trigger words)"));
        assertFalse(Files.exists(tempDir.resolve("plain_checkpoint.json")));
    }

    @Test
    void shouldSkipSidecarWhoseModelFileIsMissing() throws Exception {
        Files.writeString(tempDir.resolve("orphan.civitai.info"),
                "{\"trainedWords\": [\"ohwx style\"]}");

        OperationReport report = backfill(false, false);

        assertEquals(1, report.summary().get("Skipped (model missing)"));
        assertFalse(Files.exists(tempDir.resolve("orphan.json")));
    }

    @Test
    void shouldReportWithoutWritingOnDryRun() throws Exception {
        givenModel(tempDir, "my_lora", "ohwx style");

        OperationReport report = backfill(false, true);

        assertEquals(1, report.summary().get("Simulated Writes"));
        assertFalse(Files.exists(tempDir.resolve("my_lora.json")), "Dry run must not write");
    }

    @Test
    void shouldFindNestedSidecarsWhenRecursive() throws Exception {
        givenModel(tempDir.resolve("Illustrious"), "nested_lora", "ohwx style");

        OperationReport report = backfill(true, false);

        assertEquals(1, report.summary().get("Trigger Words Saved"));
        assertTrue(Files.exists(tempDir.resolve("Illustrious").resolve("nested_lora.json")));
    }

    @Test
    void shouldIgnoreSubfoldersWhenNotRecursive() throws Exception {
        givenModel(tempDir.resolve("Illustrious"), "nested_lora", "ohwx style");

        OperationReport report = backfill(false, false);

        assertEquals(0, report.totalProcessed());
        assertFalse(Files.exists(tempDir.resolve("Illustrious").resolve("nested_lora.json")));
    }

    @Test
    void shouldRecordErrorForMalformedSidecarAndContinue() throws Exception {
        Files.writeString(tempDir.resolve("broken.safetensors"), "fake weights");
        Files.writeString(tempDir.resolve("broken.civitai.info"), "{ not valid json");
        givenModel(tempDir, "healthy_lora", "ohwx style");

        OperationReport report = backfill(false, false);

        assertEquals(1, report.summary().get("Errors"));
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().getFirst().contains("broken"));
        assertEquals("ohwx style", activationTextOf("healthy_lora"), "Scan must continue past a bad sidecar");
    }

    @Test
    void shouldPreserveExistingUserMetadataKeys() throws Exception {
        givenModel(tempDir, "my_lora", "ohwx style");
        Files.writeString(tempDir.resolve("my_lora.json"),
                "{\"notes\": \"keep me\", \"preferred weight\": 0.8}");

        backfill(false, false);

        var result = new ObjectMapper().readTree(tempDir.resolve("my_lora.json").toFile());
        assertEquals("ohwx style", result.path("activation text").asText());
        assertEquals("keep me", result.path("notes").asText());
        assertEquals(0.8, result.path("preferred weight").asDouble());
    }

    @Test
    void shouldJoinMultipleTrainedWordsAsSections() throws Exception {
        givenModel(tempDir, "my_lora", "outfit1", "outfit2");

        backfill(false, false);

        assertEquals("outfit1,, outfit2", activationTextOf("my_lora"));
    }

    @Test
    void shouldStopWhenCancelled() throws Exception {
        givenModel(tempDir, "my_lora", "ohwx style");

        OperationReport report = service.backfillActivationText(tempDir, false, false,
                () -> true, total -> {}, () -> {});

        assertEquals("Operation Cancelled", report.message());
        assertFalse(Files.exists(tempDir.resolve("my_lora.json")));
    }

    @Test
    void shouldReportProgressForEachSidecar() throws Exception {
        givenModel(tempDir, "lora_a", "a");
        givenModel(tempDir, "lora_b", "b");

        AtomicInteger total = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        service.backfillActivationText(tempDir, false, false,
                () -> false, total::set, completed::incrementAndGet);

        assertEquals(2, total.get());
        assertEquals(2, completed.get());
    }
}
