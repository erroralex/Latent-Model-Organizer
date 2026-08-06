package com.nilsson.lmo;

import com.nilsson.lmo.api.SecurityFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Regression coverage for {@code /api/version} and CORS preflight handling.</p>
 *
 * <p>The StatusPill in the Electron titlebar and {@code SettingsModal.vue} both poll
 * {@code GET /api/version} with an {@code Authorization} header from the renderer's
 * origin. That cross-origin request with a custom header triggers a browser CORS
 * preflight -- an {@code OPTIONS} request -- before the real {@code GET} is ever sent.
 * {@code VersionHandler} never learned to answer {@code OPTIONS} (unlike every sibling
 * handler in {@code LmoApplication}), so it replied {@code 405 Method Not Allowed} to the
 * preflight and the browser never issued the real request. The StatusPill therefore
 * always rendered "Backend: Offline" and the Settings dialog silently fell back to
 * "vdev", even against a healthy backend.</p>
 *
 * <p>This test exercises the real HTTP path -- a live {@link HttpServer} wired exactly
 * as {@link LmoApplication#main} wires it, including the {@link SecurityFilter} -- so it
 * proves both that the preflight is answered without a token and that the real request
 * still works afterwards.</p>
 */
class VersionEndpointPreflightTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        var securityFilter = new SecurityFilter("test-handshake-token");
        LmoApplication.createSecureContext(server, "/api/version",
                new LmoApplication.VersionHandler(), securityFilter);

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void optionsPreflightToVersionEndpointReturns204WithoutAToken() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/version"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode(),
                "OPTIONS preflight to /api/version must succeed so the browser sends the real GET");
    }

    @Test
    void getVersionStillReturns200WithVersionBodyAfterPreflight() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/version"))
                .header("Authorization", "Bearer test-handshake-token")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"version\""),
                "expected a version field in the response body, got: " + response.body());
    }
}
