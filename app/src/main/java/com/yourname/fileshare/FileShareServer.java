package com.yourname.fileshare;

import android.content.Context;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileShareServer extends NanoHTTPD {

    private final Context context;
    private final FileStore fileStore;

    public FileShareServer(Context context, int port) {
        super(port);
        this.context = context;
        this.fileStore = new FileStore(context);
    }

    public FileStore getFileStore() {
        return fileStore;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if (Method.GET.equals(method) && ("/".equals(uri) || "/index.html".equals(uri))) {
                return serveAsset("index.html");
            }

            if (Method.GET.equals(method) && "/api/my-ip".equals(uri)) {
                String ip = NetworkUtils.getLocalIpAddress();
                return jsonResponse("{\"ip\":\"" + ip + "\"}");
            }

            if (Method.GET.equals(method) && "/api/is-local".equals(uri)) {
                return jsonResponse("{\"is_local\":false}");
            }

            if (Method.GET.equals(method) && "/api/files".equals(uri)) {
                return listFiles();
            }

            // Sửa lại phần bắt route Download để nhận được cả ID và Tên file
            if (Method.GET.equals(method) && uri.startsWith("/download/")) {
                return downloadFile(uri.substring("/download/".length()));
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
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Error: " + e.getMessage());
        }
    }

    private Response serveAsset(String name) {
        try {
            InputStream is = context.getAssets().open(name);
            Response r = newChunkedResponse(Response.Status.OK, MIME_HTML, is);
            r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                    "Asset not found: " + name);
        }
    }

    private Response jsonResponse(String json) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response listFiles() {
        List<File> files = fileStore.list();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"idx\":").append(i).append(",")
              .append("\"name\":\"").append(escapeJson(f.getName())).append("\",")
              .append("\"size\":").append(f.length()).append(",")
              // SỬA Ở ĐÂY: Thêm tên file vào sau chỉ số ID để WebView biết đuôi file
              .append("\"url\":\"/download/").append(i).append("/").append(urlEncode(f.getName())).append("\"")
              .append("}");
        }
        sb.append("]}");
        return jsonResponse(sb.toString());
    }

    private Response downloadFile(String urlPath) {
        String idxStr = urlPath;
        // Nếu URL có dạng 0/ten-file.pdf, ta chỉ lấy phần số "0"
        if (urlPath.contains("/")) {
            idxStr = urlPath.split("/")[0];
        }
        
        int idx;
        try {
            idx = Integer.parseInt(idxStr);
        } catch (NumberFormatException e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad index");
        }

        List<File> files = fileStore.list();
        if (idx < 0 || idx >= files.size()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }

        File file = files.get(idx);
        try {
            FileInputStream fis = new FileInputStream(file);
            Response r = newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
            
            // SỬA Ở ĐÂY: Header chuẩn để Android nhận diện đúng tên file
            r.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            r.addHeader("Content-Length", String.valueOf(file.length()));
            return r;
        } catch (FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
    }

    private Response handleUpload(IHTTPSession session) {
        try {
            Map<String, String> tmpFiles = new HashMap<>();
            session.parseBody(tmpFiles);

            Map<String, List<String>> params = session.getParameters();
            String originalName = null;

            if (params != null) {
                List<String> names = params.get("files");
                if (names != null && !names.isEmpty()) {
                    originalName = names.get(0);
                }
            }
            if (originalName == null) originalName = "uploaded_file";

            String tempPath = tmpFiles.get("files");
            if (tempPath == null) {
                return jsonResponse("{\"ok\":false,\"error\":\"No files field\"}");
            }

            File dest = fileStore.newFile(originalName);
            File temp = new File(tempPath);

            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }

            fileStore.add(dest);

            String resp = "{\"ok\":true,\"saved\":[{\"name\":\"" + escapeJson(dest.getName())
                    + "\",\"size\":" + dest.length() + "}]}";
            return jsonResponse(resp);

        } catch (Exception e) {
            e.printStackTrace();
            return jsonResponse("{\"ok\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private Response handleRemove(IHTTPSession session) {
        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String json = body.get("postData");
            if (json == null) {
                return jsonResponse("{\"ok\":false,\"error\":\"No body\"}");
            }

            String name = extractJsonField(json, "name");
            if (name == null) {
                return jsonResponse("{\"ok\":false,\"error\":\"Missing name\"}");
            }

            boolean ok = fileStore.removeByName(name);
            return jsonResponse("{\"ok\":" + ok + "}");

        } catch (Exception e) {
            return jsonResponse("{\"ok\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String extractJsonField(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }
}
