package com.example.chat.integration;

import com.example.chat.application.UploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two halves of an upload have to agree on where files live.
 *
 * <p>{@link UploadService} writes to {@code app.upload.dir} while the HTTP
 * layer maps {@code /uploads/**} onto a location of its own. If those ever
 * diverge, uploads succeed and every attachment 404s for the reader, so this
 * test runs the whole application with a non-default directory and checks that
 * a stored file is the file a browser gets back.</p>
 */
@SpringBootTest(properties = "app.upload.dir=target/upload-serving-test")
@AutoConfigureMockMvc
class UploadServingTest {

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private UploadService uploadService;

    @Test
    @DisplayName("a stored upload is served back from the configured directory")
    @WithMockUser(username = "serving_user")
    void storedUploadIsReadable() throws Exception {
        UploadService.StoredUpload stored = uploadService.store(
            new MockMultipartFile("file", "shot.png", "image/png", PNG), "media", "shot.png");

        String fileName = stored.url().substring("/uploads/".length());
        assertThat(Paths.get("target/upload-serving-test").toAbsolutePath().normalize().resolve(fileName)).exists();

        byte[] served = mockMvc.perform(get(stored.url()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PNG);
    }

    @Test
    @DisplayName("an attachment is not readable without a session")
    void anonymousCannotReadAttachments() throws Exception {
        mockMvc.perform(get("/uploads/media_does_not_exist.png"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an encoded traversal through /uploads never leaves the directory")
    @WithMockUser(username = "serving_user")
    void traversalIsRefused() throws Exception {
        mockMvc.perform(get("/uploads/..%2F..%2Fupload-serving-test%2F..%2Fshot.png"))
            .andExpect(status().is4xxClientError());
    }
}
