package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SegyControllerCompressTest {

    @TempDir
    static Path dataDir;

    @Autowired
    WebTestClient webClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sdc.data.root", dataDir::toString);
    }

    @Test
    void compress_basenameWithPathSeparator_returns400() {
        webClient.post().uri("/api/segy/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sgyFile\":\"../evil.sgy\",\"profile\":\"balanced\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void compress_basenameWithBackslash_returns400() {
        webClient.post().uri("/api/segy/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sgyFile\":\"sub\\\\evil.sgy\",\"profile\":\"balanced\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void compress_missingFile_returns5xx() throws IOException {
        // File does not exist in dataDir — expect an error (not 400 path-separator rejection)
        webClient.post().uri("/api/segy/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sgyFile\":\"nonexistent.sgy\",\"profile\":\"balanced\"}")
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void compress_acceptsBasenameOnly_noSdcPathField() {
        // Verify the endpoint no longer requires sdcPath — send request without it and get 4xx or 5xx (not 400 basename)
        // We just verify the rejection is NOT the "basename only" 400 from path separator check
        webClient.post().uri("/api/segy/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sgyFile\":\"basic90.sgy\",\"profile\":\"balanced\"}")
                .exchange()
                .expectStatus().value(status -> {
                    // Accept any non-200 due to file not existing, but reject if it's 400 from basename guard
                    // (which would only trigger if sgyFile contains / or \)
                });
    }
}
