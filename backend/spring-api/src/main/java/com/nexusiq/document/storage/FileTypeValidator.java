package com.nexusiq.document.storage;

import com.nexusiq.common.exception.ValidationException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Whitelists PDF/DOCX/TXT/MD and verifies the declared extension against the
 * file's actual bytes rather than trusting it (.claude/rules/security.md).
 * PDF and DOCX have real magic numbers; TXT/MD do not, so "is this plausibly
 * text" (valid UTF-8, no embedded NUL bytes) is the equivalent check for them —
 * it still rejects a binary file renamed to `.txt`.
 */
public final class FileTypeValidator {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    // DOCX is a ZIP container; the local file header signature is PK\x03\x04.
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private FileTypeValidator() {}

    public enum Format {
        PDF,
        DOCX,
        TXT,
        MD
    }

    public static Format detectFromExtension(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return Format.PDF;
        if (lower.endsWith(".docx")) return Format.DOCX;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return Format.MD;
        if (lower.endsWith(".txt")) return Format.TXT;
        throw new ValidationException(
                "Unsupported file type. Only PDF, DOCX, TXT and MD are accepted.");
    }

    /** @param header the first bytes of the upload — a few KB is enough for every case here. */
    public static void validate(Format declared, byte[] header) {
        boolean valid =
                switch (declared) {
                    case PDF -> startsWith(header, PDF_MAGIC);
                    case DOCX -> startsWith(header, ZIP_MAGIC);
                    case TXT, MD -> looksLikeText(header);
                };
        if (!valid) {
            throw new ValidationException(
                    "File content does not match its declared type (" + declared + ")");
        }
    }

    private static boolean startsWith(byte[] header, byte[] magic) {
        if (header.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (header[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeText(byte[] header) {
        for (byte b : header) {
            if (b == 0) {
                return false;
            }
        }
        // The header may end mid-codepoint since it is an arbitrary byte prefix,
        // not a codepoint-aligned slice — trim up to 3 trailing bytes (the widest
        // a UTF-8 codepoint gets) before concluding the content isn't text.
        int maxTrim = Math.min(3, header.length);
        for (int trim = 0; trim <= maxTrim; trim++) {
            int length = header.length - trim;
            try {
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(header, 0, length));
                return true;
            } catch (CharacterCodingException ignored) {
                // try trimming another trailing byte
            }
        }
        return false;
    }
}
