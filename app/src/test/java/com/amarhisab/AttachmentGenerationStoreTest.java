package com.amarhisab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AttachmentGenerationStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stagingDoesNotReplaceLegacyFilesBeforePreferenceSwitch() throws Exception {
        File root = temporary.newFolder("attachments");
        File legacy = new File(root, "legacy-file");
        write(legacy, "old-document");

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("new-document", bytes("new-document"));
        String generation = AttachmentGenerationStore.stage(root, files);

        assertTrue(legacy.isFile());
        assertEquals("old-document", read(legacy));
        assertEquals("new-document",
            read(new File(AttachmentGenerationStore.directory(root, generation), "new-document")));
        assertTrue(AttachmentGenerationStore.isValidGeneration(generation));
    }

    @Test public void cleanupKeepsCommittedGenerationAndRemovesAbandonedRestore() throws Exception {
        File root = temporary.newFolder("attachments");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("active-file", bytes("active"));
        String active = AttachmentGenerationStore.stage(root, files);
        files.clear();
        files.put("abandoned-file", bytes("abandoned"));
        String abandoned = AttachmentGenerationStore.stage(root, files);

        AttachmentGenerationStore.cleanupOrphanGenerations(root, active);

        assertTrue(new File(root, active).isDirectory());
        assertFalse(new File(root, abandoned).exists());
        assertEquals("active",
            read(new File(AttachmentGenerationStore.directory(root, active), "active-file")));
    }

    @Test public void switchingToGenerationRemovesLegacyFilesButKeepsNewFiles() throws Exception {
        File root = temporary.newFolder("attachments");
        File legacy = new File(root, "legacy-file");
        write(legacy, "legacy");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("restored-file", bytes("restored"));
        String active = AttachmentGenerationStore.stage(root, files);

        AttachmentGenerationStore.cleanupOrphanGenerations(root, active);

        assertFalse(legacy.exists());
        assertEquals("restored",
            read(new File(AttachmentGenerationStore.directory(root, active), "restored-file")));
    }

    @Test public void invalidAttachmentIdIsRejectedBeforeAnyGenerationIsCreated() throws Exception {
        File root = temporary.newFolder("attachments");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("../outside", bytes("unsafe"));
        try {
            AttachmentGenerationStore.stage(root, files);
            fail("Expected invalid attachment identifier to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(root.isDirectory());
            assertEquals(0, root.list().length);
        }
    }

    @Test public void emptyLegacyGenerationDeletesOnlyFlatFiles() throws Exception {
        File root = temporary.newFolder("attachments");
        File legacy = new File(root, "legacy-file");
        write(legacy, "legacy");
        File staged = new File(root, "gen_keep");
        assertTrue(staged.mkdir());

        AttachmentGenerationStore.deleteGeneration(root, "");

        assertFalse(legacy.exists());
        assertTrue(staged.isDirectory());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes(value));
            out.flush();
        }
    }

    private static String read(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = in.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            assertEquals(data.length, offset);
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
