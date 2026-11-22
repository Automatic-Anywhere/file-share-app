package com.yourname.fileshare;

import android.content.Context;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileShareServer extends NanoHTTPD {

    private final Context context;
    private final FileStore fileStore;
    private final File noteFile;

    public FileShareServer(Context context, int port) {
        super(port);
        this.context = context;
        this.fileStore = new FileStore(context);
        this.noteFile = new File(context.getFilesDir(), "notes.json");
    }

    public void clearAllData() {
        fileStore.clearAll();
        if (noteFile.exists()) noteFile.delete();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if (Method.GET.equals(method) && ("/".equals(uri) || "/index.html".equals(uri))) {
                return serveAsset("index.html", MIME_HTML);
            }
            if (Method.GET.equals(method) && "/download-apk".equals(uri)) {
                String[] assets = context.getAssets().list("");
                if (assets != null) {
                    for (String file : assets) {
                        if (file.endsWith(".apk")) return serveAsset(file, "application/vnd.android.package-archive");
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No APK");
            }
            if (Method.GET.equals(method) && "/api/my-ip".equals(uri)) {
                return jsonResponse("{\"ip\":\"" + NetworkUtils.getLocalIpAddress() + "\"}");
            }
            if (Method.GET.equals(method) && "/api/files".equals(uri)) {
                return listFiles();
            }
            if (Method.GET.equals(method) && uri.startsWith("/download/")) {
                return downloadFile(uri.substring("/download/".length()));
            }
            
            // --- API VĂN BẢN ---
            if (Method.GET.equals(method) && "/api/text".equals(uri)) {
                return getNotes();
            }
            if (Method.POST.equals(method) && "/api/text/save".equals(uri)) {
                return saveNotes(session);
            }

            if (Method.POST.equals(method) && "/api/upload".equals(uri)) {
                return handleUpload(session);
            }
            if (Method.POST.equals(method) && "/api/remove-file".equals(uri)) {
                return handleRemove(session);
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error");
        }
    }

    // ================== NOTES LOGIC ==================
    private Response getNotes() {
        if (!noteFile.exists()) return jsonResponse("[]");
        try {
            FileInputStream fis = new FileInputStream(noteFile);
            byte[] data = new byte[(int) noteFile.length()];
            fis.read(data); fis.close();
            return jsonResponse(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return jsonResponse("[]");
        }
    }

    private Response saveNotes(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String jsonArray = body.get("postData");
            
            // Nếu có data, ghi thẳng mảng JSON xuống file
            if (jsonArray != null) {
                try (FileOutputStream fos = new FileOutputStream(noteFile)) {
                    fos.write(jsonArray.getBytes(StandardCharsets.UTF_8));
                }
            }
            return jsonResponse("{\"ok\":true}");
        } catch (Exception e) {
            return jsonResponse("{\"ok\":false}");
        }
    }

    // ================== FILE LOGIC ==================
    private Response serveAsset(String name, String mime) {
        try {
            InputStream is = context.getAssets().open(name);
            Response r = newChunkedResponse(Response.Status.OK, mime, is);
            r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            if (name.endsWith(".apk")) r.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: " + name);
        }
    }

    private Response jsonResponse(String json) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response listFiles() {
        List<File> files = fileStore.list();
        StringBuilder sb = new StringBuilder("{\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"idx\":").append(i).append(",")
              .append("\"name\":\"").append(escapeJson(f.getName())).append("\",")
              .append("\"size\":").append(f.length()).append(",")
              .append("\"url\":\"/download/").append(i).append("/").append(urlEncode(f.getName())).append("\"}");
        }
        sb.append("]}");
        return jsonResponse(sb.toString());
    }

    private Response downloadFile(String urlPath) {
        String idxStr = urlPath.contains("/") ? urlPath.split("/")[0] : urlPath;
        int idx = -1;
        try { idx = Integer.parseInt(idxStr); } catch (Exception e) {}
        
        List<File> files = fileStore.list();
        if (idx < 0 || idx >= files.size()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");

        File file = files.get(idx);
        try {
            FileInputStream fis = new FileInputStream(file);
            Response r = newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
            r.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            r.addHeader("Content-Length", String.valueOf(file.length()));
            return r;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
    }

    private Response handleUpload(IHTTPSession session) {
        try {
            Map<String, String> tmpFiles = new HashMap<>();
            session.parseBody(tmpFiles);
            Map<String, List<String>> params = session.getParameters();
            String originalName = (params != null && params.get("files") != null) ? params.get("files").get(0) : "file";
            String tempPath = tmpFiles.get("files");
            
            if (tempPath != null) {
                File dest = fileStore.newFile(originalName);
                try (InputStream in = new FileInputStream(tempPath); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                fileStore.add(dest);
                return jsonResponse("{\"ok\":true}");
            }
            return jsonResponse("{\"ok\":false}");
        } catch (Exception e) {
            return jsonResponse("{\"ok\":false}");
        }
    }

    private Response handleRemove(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String json = body.get("postData");
            String name = extractJsonField(json, "name");
            boolean ok = fileStore.removeByName(name);
            return jsonResponse("{\"ok\":" + ok + "}");
        } catch (Exception e) {
            return jsonResponse("{\"ok\":false}");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    private static String extractJsonField(String json, String key) {
        if (json == null) return null;
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }
}
