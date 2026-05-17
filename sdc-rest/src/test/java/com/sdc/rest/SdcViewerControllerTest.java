package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SdcViewerControllerTest {

    @TempDir
    static Path dataDir;

    @Autowired
    WebTestClient webClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sdc.data.root", dataDir::toString);
    }

    @Test
    void listSdcFiles_returnsBasenames() throws IOException {
        Files.writeString(dataDir.resolve("basic90.sdc"), "dummy");

        webClient.get().uri("/api/viewer/files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").value((List<String> files) ->
                        assertThat(files).contains("basic90.sdc"));
    }

    @Test
    void listSgyFiles_returnsBasenames() throws IOException {
        Files.writeString(dataDir.resolve("input.sgy"), "dummy");

        webClient.get().uri("/api/viewer/sgy-files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").value((List<String> files) ->
                        assertThat(files).contains("input.sgy"));
    }

    @Test
    void listFiles_extensionFiltering_noMixedResults() throws IOException {
        // .sdc endpoint must not return .sgy files and vice versa
        Files.writeString(dataDir.resolve("a.sdc"), "dummy");
        Files.writeString(dataDir.resolve("b.sgy"), "dummy");

        webClient.get().uri("/api/viewer/files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").value((List<String> files) -> {
                    assertThat(files).allMatch(f -> f.endsWith(".sdc"));
                    assertThat(files).noneMatch(f -> f.endsWith(".sgy"));
                });

        webClient.get().uri("/api/viewer/sgy-files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").value((List<String> files) -> {
                    assertThat(files).allMatch(f -> f.endsWith(".sgy"));
                    assertThat(files).noneMatch(f -> f.endsWith(".sdc"));
                });
    }
}
