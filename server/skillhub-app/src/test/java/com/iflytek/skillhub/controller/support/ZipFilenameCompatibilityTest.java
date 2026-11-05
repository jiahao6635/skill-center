package com.iflytek.skillhub.controller.support;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ZipFilenameCompatibilityTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesUtf8ChineseNamesAndContents(boolean clawHub) throws Exception {
        assertChineseEntries(clawHub, StandardCharsets.UTF_8, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesGbkNamesAfterReadingAsciiEntry(boolean clawHub) throws Exception {
        assertChineseEntries(clawHub, Charset.forName("GBK"), 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prefersUtf8WhenLanguageFlagIsMissing(boolean clawHub) throws Exception {
        byte[] zip = createZip(StandardCharsets.UTF_8, "历史用例.md", new byte[]{1});
        zip[7] &= (byte) ~8; // Clear the local header's UTF-8 language flag.
        var file = spy(new MockMultipartFile("file", zip));

        assertEquals("历史用例.md", extract(clawHub, new SkillPublishProperties(), file).getFirst().path());
        verify(file, times(1)).getInputStream();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stillRejectsTraversalAfterGbkRetry(boolean clawHub) throws Exception {
        var file = new MockMultipartFile("file", createZip(Charset.forName("GBK"), "../历史用例.md", new byte[]{1}));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> extract(clawHub, new SkillPublishProperties(), file));
        String detail = clawHub ? ((DomainBadRequestException) error).messageArgs()[0].toString() : error.getMessage();
        assertTrue(detail.contains("path"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stillEnforcesSizeLimitsAfterGbkRetry(boolean clawHub) throws Exception {
        var props = new SkillPublishProperties();
        props.setMaxSingleFileSize(2);
        var file = new MockMultipartFile("file", createZip(Charset.forName("GBK"), "历史用例.md", new byte[]{1, 2, 3}));

        RuntimeException error = assertThrows(RuntimeException.class, () -> extract(clawHub, props, file));
        String detail = clawHub ? ((DomainBadRequestException) error).messageArgs()[0].toString() : error.getMessage();
        assertTrue(detail.contains("File too large"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotRetryCorruptedContent(boolean clawHub) throws Exception {
        byte[] zip = createZip(StandardCharsets.UTF_8, "README.md", new byte[]{1});
        zip[30 + "README.md".length()] ^= 1; // Damage the stored data, preserving its original CRC.
        var file = spy(new MockMultipartFile("file", zip));

        assertThrows(ZipException.class, () -> extract(clawHub, new SkillPublishProperties(), file));
        verify(file, times(1)).getInputStream();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsInvalidNamesWithActionableMessage(boolean clawHub) throws Exception {
        byte[] zip = createZip(StandardCharsets.UTF_8, "a.md", new byte[]{1});
        zip[30] = (byte) 0xff; // Invalid UTF-8, even when the archive declares UTF-8.
        var file = new MockMultipartFile("file", zip);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extract(clawHub, new SkillPublishProperties(), file));
        assertTrue(error.getMessage().contains("use UTF-8 or GBK"));
    }

    private void assertChineseEntries(boolean clawHub, Charset charset, int attempts) throws Exception {
        byte[] content = "中文正文".getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, charset)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(content);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ToBid_历史用例参考/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ToBid_历史用例参考/中文用例.md"));
            zip.write(content);
            zip.closeEntry();
        }
        var file = spy(new MockMultipartFile("file", output.toByteArray()));

        List<PackageEntry> entries = extract(clawHub, new SkillPublishProperties(), file);
        assertEquals(List.of("SKILL.md", "ToBid_历史用例参考/中文用例.md"),
                entries.stream().map(PackageEntry::path).toList());
        entries.forEach(entry -> assertArrayEquals(content, entry.content()));
        verify(file, times(attempts)).getInputStream();
    }

    private List<PackageEntry> extract(boolean clawHub, SkillPublishProperties props,
                                       MockMultipartFile file) throws Exception {
        return clawHub ? new ZipPackageExtractor(props).extract(file)
                : new SkillPackageArchiveExtractor(props).extract(file);
    }

    private byte[] createZip(Charset charset, String name, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, charset)) {
            var crc = new CRC32();
            crc.update(content);
            var entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
