package com.nexusiq.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusiq.common.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileTypeValidatorTest {

    @Test
    void detectFromExtension_mapsKnownExtensions() {
        assertThat(FileTypeValidator.detectFromExtension("policy.pdf")).isEqualTo(FileTypeValidator.Format.PDF);
        assertThat(FileTypeValidator.detectFromExtension("policy.PDF")).isEqualTo(FileTypeValidator.Format.PDF);
        assertThat(FileTypeValidator.detectFromExtension("policy.docx")).isEqualTo(FileTypeValidator.Format.DOCX);
        assertThat(FileTypeValidator.detectFromExtension("notes.md")).isEqualTo(FileTypeValidator.Format.MD);
        assertThat(FileTypeValidator.detectFromExtension("notes.txt")).isEqualTo(FileTypeValidator.Format.TXT);
    }

    @Test
    void detectFromExtension_rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> FileTypeValidator.detectFromExtension("payload.exe"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validate_acceptsRealPdfHeader() {
        byte[] header = "%PDF-1.7\n%âãÏÓ".getBytes(StandardCharsets.ISO_8859_1);
        FileTypeValidator.validate(FileTypeValidator.Format.PDF, header);
    }

    @Test
    void validate_rejectsTextRenamedToPdf() {
        byte[] header = "just plain text, not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> FileTypeValidator.validate(FileTypeValidator.Format.PDF, header))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validate_acceptsRealDocxZipHeader() {
        byte[] header = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
        FileTypeValidator.validate(FileTypeValidator.Format.DOCX, header);
    }

    @Test
    void validate_rejectsPdfRenamedToDocx() {
        byte[] header = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> FileTypeValidator.validate(FileTypeValidator.Format.DOCX, header))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validate_acceptsPlainUtf8TextForTxtAndMd() {
        byte[] header = "# Heading\n\nSome policy text with an em-dash — and more.".getBytes(StandardCharsets.UTF_8);
        FileTypeValidator.validate(FileTypeValidator.Format.TXT, header);
        FileTypeValidator.validate(FileTypeValidator.Format.MD, header);
    }

    @Test
    void validate_acceptsUtf8TextEvenWhenHeaderTruncatesMidCodepoint() {
        // "—" (em dash) is 3 bytes in UTF-8; build a header that ends exactly
        // one byte into it so the decoder would see an incomplete sequence.
        byte[] full = "policy text ending in an em dash —".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = new byte[full.length - 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        FileTypeValidator.validate(FileTypeValidator.Format.TXT, truncated);
    }

    @Test
    void validate_rejectsBinaryContentRenamedToTxt() {
        byte[] header = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x10, 0x20};
        assertThatThrownBy(() -> FileTypeValidator.validate(FileTypeValidator.Format.TXT, header))
                .isInstanceOf(ValidationException.class);
    }
}
