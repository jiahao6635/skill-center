package com.iflytek.skillhub.controller.support;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Filename decoding shared by the portal, CLI, and ClawHub archive readers. */
final class ZipFilenameEncoding {

    static final Charset GBK = Charset.forName("GBK");
    // ponytail: unflagged names share one archive-wide charset; use per-entry decoding if mixed archives are required.

    private ZipFilenameEncoding() {
    }

    static ZipEntry nextEntry(ZipInputStream input, Charset charset) throws IOException {
        try {
            return input.getNextEntry();
        } catch (IllegalArgumentException ex) {
            // Only filename decoding failures trigger a retry, never path or size validation errors.
            if (ex.getCause() instanceof CharacterCodingException codingException) {
                if (!StandardCharsets.UTF_8.equals(charset)) {
                    throw new IllegalArgumentException(
                            "Invalid ZIP filename encoding; use UTF-8 or GBK when creating the archive", ex);
                }
                throw codingException;
            }
            throw ex;
        }
    }
}
