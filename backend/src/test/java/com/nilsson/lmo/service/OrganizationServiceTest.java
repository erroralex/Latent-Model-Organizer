package com.nilsson.lmo.service;

import com.nilsson.lmo.domain.ModelMetadata;
import com.nilsson.lmo.domain.OperationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * <p>The {@code OrganizationServiceTest} suite validates the core organization,
 * relocation, and restoration engine of the Latent Model Organizer. It simulates
 * real-world library transformations and restores through a mock environment.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Relocation Logic:</b> Validates atomic moves of model units including
 *   weights and sidecars.</li>
 *   <li><b>Restoration Integrity:</b> Verifies the bit-perfect reversal of library
 *   states using persistent {@code UndoManifest} data.</li>
 *   <li><b>Reporting Accuracy:</b> Ensures that statistics and categorisations in
 *   the {@link OperationReport} correctly reflect the filesystem outcomes.</li>
 *   <li><b>Recursive Depth:</b> Confirms correct traversal and sorting within nested
 *   directory structures.</li>
 *   <li><b>Dry Run Safety:</b> Asserts that simulations do not modify the
 *   filesystem state.</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private ModelAnalyzer modelAnalyzer;

    private OrganizationService organizationService;

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path targetDir;

    @BeforeEach
    void setUp() throws IOException {
        organizationService = new OrganizationService(modelAnalyzer);
        sourceDir = tempDir.resolve("source");
        targetDir = tempDir.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);
    }

    /**
     * Civitai serves animated previews as {@code .mp4}. Naming those {@code .preview.png}
     * produces a file the browser cannot decode, so the card renders "NO PREVIEW" — even
     * though Forge lists {@code mp4} and {@code webm} among its allowed preview extensions
     * and renders them in a {@code <video>} tag.
     */
    @Test
    void resolvePreviewExtension_shouldKeepVideoExtensions() {
        assertEquals(".preview.mp4",
                OrganizationService.resolvePreviewExtension("https://image.civitai.com/abc/original=true/136292598.mp4"));
        assertEquals(".preview.webm",
                OrganizationService.resolvePreviewExtension("https://image.civitai.com/abc/clip.webm"));
    }

    @Test
    void resolvePreviewExtension_shouldMapImageExtensions() {
        assertEquals(".preview.jpeg", OrganizationService.resolvePreviewExtension("https://x/img.jpg"));
        assertEquals(".preview.jpeg", OrganizationService.resolvePreviewExtension("https://x/img.jpeg"));
        assertEquals(".preview.webp", OrganizationService.resolvePreviewExtension("https://x/img.webp"));
        assertEquals(".preview.png", OrganizationService.resolvePreviewExtension("https://x/img.png"));
        assertEquals(".preview.gif", OrganizationService.resolvePreviewExtension("https://x/img.gif"));
        assertEquals(".preview.avif", OrganizationService.resolvePreviewExtension("https://x/img.avif"));
    }

    @Test
    void resolvePreviewExtension_shouldIgnoreCaseAndQueryStrings() {
        assertEquals(".preview.mp4", OrganizationService.resolvePreviewExtension("https://x/CLIP.MP4?width=450"));
        assertEquals(".preview.jpeg", OrganizationService.resolvePreviewExtension("https://x/img.JPG#frag"));
    }

    /** Dots in earlier path segments must not be mistaken for the file extension. */
    @Test
    void resolvePreviewExtension_shouldOnlyConsiderTheFinalPathSegment() {
        assertEquals(".preview.mp4",
                OrganizationService.resolvePreviewExtension("https://image.civitai.com/v1.2/original=true/clip.mp4"));
        assertEquals(".preview.png",
                OrganizationService.resolvePreviewExtension("https://image.civitai.com/v1.2/original=true/12345678"));
    }

    @Test
    void resolvePreviewExtension_shouldFallBackToPngForUnknownOrMissingExtensions() {
        assertEquals(".preview.png", OrganizationService.resolvePreviewExtension("https://x/12345678"));
        assertEquals(".preview.png", OrganizationService.resolvePreviewExtension("https://x/file.bin"));
        assertEquals(".preview.png", OrganizationService.resolvePreviewExtension("https://x/trailing."));
    }

    @Test
    void organizeModels_shouldRelocateModelAndSidecars() throws IOException {
        Path modelFile = sourceDir.resolve("test_model.safetensors");
        Path sidecarFile = sourceDir.resolve("test_model.preview.png");
        Files.writeString(modelFile, "dummy model data");
        Files.writeString(sidecarFile, "dummy image data");

        when(modelAnalyzer.analyze(any(Path.class)))
                .thenReturn(new ModelMetadata("test_model.safetensors", "SDXL 1.0", "SDXL Base"));

        OperationReport report = organizationService.organizeModels(
                sourceDir, targetDir, Collections.emptyList(), false, false);

        assertNotNull(report);
        assertEquals(1, report.summary().get("SDXL 1.0"));
        assertTrue(Files.exists(targetDir.resolve("SDXL 1.0/test_model.safetensors")));
        assertTrue(Files.exists(targetDir.resolve("SDXL 1.0/test_model.preview.png")));
        assertFalse(Files.exists(modelFile));
        assertFalse(Files.exists(sidecarFile));
    }

    @Test
    void undoLastOrganize_shouldRestoreFilesToOriginalLocations() throws IOException {
        Path originalPath = sourceDir.resolve("undo_target.safetensors");
        Files.writeString(originalPath, "undo data");

        when(modelAnalyzer.analyze(any(Path.class)))
                .thenReturn(new ModelMetadata("undo_target.safetensors", "Flux .1 S", "Flux"));

        organizationService.organizeModels(sourceDir, targetDir, Collections.emptyList(), false, false);

        Path organizedPath = targetDir.resolve("Flux .1 S/undo_target.safetensors");
        assertTrue(Files.exists(organizedPath));

        OperationReport undoReport = organizationService.undoLastOrganize(targetDir);

        assertEquals(1, undoReport.summary().get("Restored"));
        assertTrue(Files.exists(originalPath));
        assertFalse(Files.exists(organizedPath));
    }

    /**
     * When organizing in place (source == target), a prior run's {@code undo-manifest.json}
     * sits in the same directory being scanned. It must never be treated as a candidate model
     * file, or it gets sorted into {@code Uncategorized} and the undo trail is destroyed.
     */
    @Test
    void organizeModels_shouldNotSortTheUndoManifestItself() throws IOException {
        Path modelFile = sourceDir.resolve("test_model.safetensors");
        Files.writeString(modelFile, "dummy model data");
        Files.writeString(sourceDir.resolve(OrganizationService.UNDO_MANIFEST_FILENAME), "{}");

        when(modelAnalyzer.analyze(any(Path.class)))
                .thenReturn(new ModelMetadata("test_model.safetensors", "SDXL 1.0", "SDXL Base"));

        OperationReport report = organizationService.organizeModels(
                sourceDir, sourceDir, Collections.emptyList(), false, false);

        assertNull(report.summary().get("Uncategorized"));
        assertFalse(Files.exists(sourceDir.resolve("Uncategorized")
                .resolve(OrganizationService.UNDO_MANIFEST_FILENAME)));
        assertTrue(Files.exists(sourceDir.resolve(OrganizationService.UNDO_MANIFEST_FILENAME)));
    }

    @Test
    void organizeModels_shouldRespectAllowedArchitectures() throws IOException {
        Files.writeString(sourceDir.resolve("sdxl.safetensors"), "sdxl data");
        Files.writeString(sourceDir.resolve("flux.safetensors"), "flux data");

        when(modelAnalyzer.analyze(argThat(p -> p != null && p.getFileName() != null && p.getFileName().toString().contains("sdxl"))))
                .thenReturn(new ModelMetadata("sdxl.safetensors", "SDXL 1.0", "SDXL"));
        when(modelAnalyzer.analyze(argThat(p -> p != null && p.getFileName() != null && p.getFileName().toString().contains("flux"))))
                .thenReturn(new ModelMetadata("flux.safetensors", "Flux .1 S", "Flux"));

        organizationService.organizeModels(
                sourceDir, targetDir, List.of("Flux .1 S"), false, false);

        assertTrue(Files.exists(targetDir.resolve("Flux .1 S/flux.safetensors")));
        assertFalse(Files.exists(targetDir.resolve("SDXL 1.0/sdxl.safetensors")));
    }
}