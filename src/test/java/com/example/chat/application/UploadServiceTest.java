package com.example.chat.application;

import com.example.chat.exception.ChatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'
    };
    private static final byte[] JPEG = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0 };
    private static final byte[] WEBP = "RIFFxxxxWEBP".getBytes();
    private static final byte[] PDF = "%PDF-1.7 ".getBytes();
    private static final byte[] DOS_EXE = "MZ\u0090\u0003\u0000\u0000\u0000\u0004".getBytes();

    private UploadService service() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("uploads-test");
        return new UploadService(dir.toString(), 1024);
    }

    @Test
    @DisplayName("a real PNG is accepted even when the client lies about its name and type")
    void trustsMagicBytesNotClientMetadata() throws Exception {
        UploadService service = service();
        MockMultipartFile file = new MockMultipartFile("file", "..\\..\\windows\\system32\\evil.exe",
            "application/x-msdownload", PNG);

        UploadService.StoredUpload stored = service.store(file, "media", "report.png");

        assertThat(stored.type()).isEqualTo("image/png");
        assertThat(stored.url()).matches("/uploads/media_[0-9a-f-]{36}\\.png");
        assertThat(stored.name()).isEqualTo("report.png");
        assertThat(stored.size()).isEqualTo(PNG.length);
    }

    @Test
    @DisplayName("JPEG, GIF, WEBP and PDF magic bytes are recognised")
    void acceptedTypes() throws Exception {
        UploadService service = service();

        assertThat(service.store(new MockMultipartFile("f", "a.jpg", "image/jpeg", JPEG), "media", "a.jpg").type())
            .isEqualTo("image/jpeg");
        assertThat(service.store(new MockMultipartFile("f", "a.gif", "image/gif", "GIF89a....".getBytes()), "media", "a.gif").type())
            .isEqualTo("image/gif");
        assertThat(service.store(new MockMultipartFile("f", "a.webp", "image/webp", WEBP), "media", "a.webp").type())
            .isEqualTo("image/webp");
        assertThat(service.store(new MockMultipartFile("f", "a.pdf", "application/pdf", PDF), "media", "a.pdf").type())
            .isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("an executable wearing an image extension is refused")
    void rejectsPolyglot() throws Exception {
        UploadService service = service();
        MockMultipartFile disguised = new MockMultipartFile("f", "photo.png", "image/png", DOS_EXE);

        assertThatThrownBy(() -> service.store(disguised, "media", "photo.png"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_UPLOAD);
    }

    @Test
    @DisplayName("empty and oversized files are refused before anything touches disk")
    void rejectsEmptyAndOversized() throws Exception {
        UploadService service = service();
        byte[] big = new byte[1025];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        assertThatThrownBy(() -> service.store(new MockMultipartFile("f", "x.png", "image/png", new byte[0]), "media", "x.png"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_UPLOAD);
        assertThatThrownBy(() -> service.store(new MockMultipartFile("f", "x.png", "image/png", big), "media", "x.png"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_UPLOAD);
    }

    @Test
    @DisplayName("describe accepts only urls this service minted and that still exist")
    void describeOnlyOwnsMintedUrls() throws Exception {
        UploadService service = service();
        UploadService.StoredUpload stored = service.store(new MockMultipartFile("f", "n.png", "image/png", PNG), "media", "n.png");

        assertThat(service.describe(stored.url())).isNotNull();
        assertThat(service.describe(stored.url()).type()).isEqualTo("image/png");
        assertThat(service.describe("https://evil.example/n.png")).isNull();
        assertThat(service.describe("/uploads/../data/secret.txt")).isNull();
        assertThat(service.describe("/uploads/media_deadbeef-0000-0000-0000-000000000000.png")).isNull();
        assertThat(service.describe(null)).isNull();
    }

    @Test
    @DisplayName("display names are flattened so they cannot smuggle paths or newlines")
    void sanitizesDisplayName() throws Exception {
        UploadService service = service();

        UploadService.StoredUpload stored = service.store(new MockMultipartFile("f", "a", "image/png", PNG),
            "media", "../../etc/passwd\nx.png");

        assertThat(stored.name()).doesNotContain("/").doesNotContain("\\").doesNotContain("\n");
    }
}
