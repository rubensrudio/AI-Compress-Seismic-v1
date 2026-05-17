package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "sdc.data.root=")
class SdcViewerControllerBlankRootTest {

    @Autowired
    WebTestClient webClient;

    @Test
    void listSdcFiles_blankRoot_returnsEmptyList() {
        webClient.get().uri("/api/viewer/files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").isArray()
                .jsonPath("$.files.length()").isEqualTo(0);
    }

    @Test
    void listSgyFiles_blankRoot_returnsEmptyList() {
        webClient.get().uri("/api/viewer/sgy-files")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.files").isArray()
                .jsonPath("$.files.length()").isEqualTo(0);
    }
}
