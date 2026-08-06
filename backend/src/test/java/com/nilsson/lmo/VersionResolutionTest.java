package com.nilsson.lmo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Regression coverage for {@code GET /api/version}.</p>
 *
 * <p>The version lookup previously read {@code pom.properties} from a hardcoded Maven
 * coordinate path built from the Java package name and the jar's {@code finalName}
 * ({@code com.nilsson.lmo/backend}) rather than the real coordinates
 * ({@code com.latent/latent-model-organizer-backend}). The resource never resolved, so the
 * handler silently returned its {@code "dev"} fallback and the Settings dialog rendered
 * "vdev" in every release.</p>
 *
 * <p>The replacement reads a build-filtered {@code version.properties}, which does not
 * encode the coordinates at all and therefore survives the planned groupId unification.</p>
 */
class VersionResolutionTest {

    @Test
    void resolvesTheRealBuildVersionRatherThanTheDevFallback() {
        String version = LmoApplication.resolveVersion();

        assertNotNull(version, "version must never be null");
        assertNotEquals("dev", version,
                "version fell back to 'dev' -- the build-filtered version.properties did not resolve");
    }

    @Test
    void resolvedVersionLooksLikeAReleaseVersion() {
        String version = LmoApplication.resolveVersion();

        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-.+)?"),
                "expected a semver-shaped version from the pom, got: " + version);
    }

    @Test
    void versionPropertiesIsPresentOnTheClasspath() {
        assertNotNull(LmoApplication.class.getResourceAsStream("/version.properties"),
                "version.properties must be packaged; check resource filtering in pom.xml");
    }
}
