package com.amarhisab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Stores attachments in versioned directories so a backup restore can switch
 * the active directory with the same SharedPreferences commit as the records.
 * Old files remain untouched until the new generation is committed.
 */
public final class AttachmentGenerationStore {
    private static final long MAX_TOTAL_BYTES = 12L * 1024L * 1024L;

    private AttachmentGenerationStore() { }

    public static boolean isValidGeneration(String generation) {
        return generation != null && (generation.isEmpty() ||
            generation.matches("gen_[A-Za-z0-9_-]{1,80}"));
    }

    public static String stage(File root, Map<String, byte[]> attachments) throws IOException {
        if (root == null || attachments == null)
            throw new IOException("Attachment restore input is missing");

        long totalBytes = 0;
        for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
            if (!isValidAttachmentId(entry.getKey()))
                throw new IOException("Invalid attachment identifier");
            byte[] bytes = entry.getValue();
            if (bytes == null || bytes.length == 0)
                throw new IOException("Attachment data is empty");
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES)
                throw new IOException("Attachment restore exceeds 12 MB");
        }

        ensureDirectory(root);
        String generation = "gen_" + UUID.randomUUID().toString().replace("-", "");
        File staged = new File(root, generation);
        if (!staged.mkdir()) throw new IOException("Could not create attachment generation");

        try {
            for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
                File target = new File(staged, entry.getKey());
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(entry.getValue());
                    out.flush();
                    out.getFD().sync();
                }
            }
            return generation;
        } catch (IOException | RuntimeException error) {
            deleteTree(staged);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not stage attachment files", error);
        }
    }

    public static File directory(File root, String generation) throws IOException {
        if (root == null) throw new IOException("Attachment root is missing");
        ensureDirectory(root);
        if (generation == null || generation.isEmpty()) return root;
        if (!isValidGeneration(generation))
            throw new IOException("Invalid attachment generation");
        File active = new File(root, generation);
        ensureDirectory(active);
        return active;
    }

    /**
     * Deletes only the selected generation. An empty generation means the
     * legacy flat directory, so child generation directories are never removed.
     */
    public static void deleteGeneration(File root, String generation) {
        if (root == null || !root.isDirectory()) return;
        if (generation == null || generation.isEmpty()) {
            File[] children = root.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child.isFile()) child.delete();
            }
            return;
        }
        if (isValidGeneration(generation)) deleteTree(new File(root, generation));
    }

    /**
     * Removes abandoned restore generations after a crash. If the active
     * generation is versioned, legacy flat files are also stale; otherwise
     * legacy flat files remain active and are preserved.
     */
    public static void cleanupOrphanGenerations(File root, String activeGeneration) {
        if (root == null || !root.isDirectory()) return;
        if (activeGeneration == null || !isValidGeneration(activeGeneration))
            activeGeneration = "";
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !child.getName().equals(activeGeneration)) {
                deleteTree(child);
            } else if (!activeGeneration.isEmpty() && child.isFile()) {
                child.delete();
            }
        }
    }

    private static boolean isValidAttachmentId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,80}");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs())
            throw new IOException("Could not create attachment directory");
        if (!directory.isDirectory())
            throw new IOException("Attachment path is not a directory");
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        target.delete();
    }
}
