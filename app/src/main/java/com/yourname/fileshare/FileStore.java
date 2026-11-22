package com.yourname.fileshare;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileStore {

    private final File uploadDir;
    private final List<File> files = new ArrayList<>();

    public FileStore(Context context) {
        uploadDir = new File(context.getFilesDir(), "uploads");
        if (!uploadDir.exists()) uploadDir.mkdirs();
        clearAll();
    }

    public void clearAll() {
        files.clear();
        File[] all = uploadDir.listFiles();
        if (all != null) {
            for (File f : all) {
                f.delete();
            }
        }
    }

    public synchronized void add(File file) {
        files.add(file);
    }

    public synchronized List<File> list() {
        List<File> valid = new ArrayList<>();
        for (File f : files) {
            if (f.exists() && f.isFile()) valid.add(f);
        }
        files.clear();
        files.addAll(valid);
        return new ArrayList<>(files);
    }

    public synchronized boolean removeByName(String name) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getName().equals(name)) {
                File f = files.remove(i);
                return f.delete();
            }
        }
        return false;
    }

    public File newFile(String originalName) {
        String safe = originalName == null ? "unnamed" : originalName;
        safe = new File(safe).getName();
        if (safe.isEmpty()) safe = "unnamed";

        String base = safe;
        String ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        }

        File candidate = new File(uploadDir, safe);
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(uploadDir, base + " (" + i + ")" + ext);
            i++;
        }
        return candidate;
    }

    public File getUploadDir() {
        return uploadDir;
    }
}
