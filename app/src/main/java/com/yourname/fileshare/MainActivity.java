package com.yourname.fileshare;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;
    private static final int FILE_CHOOSER_REQUEST = 1;
    
    private FileShareServer server;
    private WebView webView;
    private LinearLayout selectionLayout, webLayout, clientLayout;
    private ValueCallback<Uri[]> filePathCallback;

    private List<String> historyList = new ArrayList<>();
    private Map<String, Boolean> onlineStatus = new HashMap<>(); // Lưu trạng thái ping
    private ArrayAdapter<String> historyAdapter;
    private SharedPreferences prefs;

    // Bộ đếm ping 3s
    private Handler pingHandler = new Handler(Looper.getMainLooper());
    private ExecutorService pingExecutor = Executors.newCachedThreadPool();
    private Runnable pingRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("FileSharePrefs", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        selectionLayout = findViewById(R.id.selectionLayout);
        webLayout = findViewById(R.id.webLayout);
        clientLayout = findViewById(R.id.clientLayout);

        // --- MÀN HÌNH CHỌN ---
        findViewById(R.id.btnHost).setOnClickListener(v -> startHostMode());
        findViewById(R.id.btnClient).setOnClickListener(v -> showClientMode());

        // --- MÀN HÌNH CLIENT ---
        findViewById(R.id.btnBackToSelection).setOnClickListener(v -> {
            clientLayout.setVisibility(View.GONE);
            selectionLayout.setVisibility(View.VISIBLE);
            pingHandler.removeCallbacks(pingRunnable); // Dừng ping khi quay lại
        });

        findViewById(R.id.btnConnectIp).setOnClickListener(v -> {
            EditText et = findViewById(R.id.etIpAddress);
            String ip = et.getText().toString().trim();
            if (ip.isEmpty()) { Toast.makeText(this, "Vui lòng nhập IP", Toast.LENGTH_SHORT).show(); return; }
            if (!ip.startsWith("http")) ip = "http://" + ip;
            if (!ip.contains(":")) ip += ":8000";
            connectToServer(ip);
        });

        findViewById(R.id.btnScanQr).setOnClickListener(v -> startQrScan());

        // --- SETUP LỊCH SỬ ---
        ListView lvHistory = findViewById(R.id.lvHistory);
        loadHistory();
        
        historyAdapter = new ArrayAdapter<String>(this, R.layout.item_history, historyList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) convertView = getLayoutInflater().inflate(R.layout.item_history, parent, false);
                
                String url = getItem(position);
                TextView tvUrl = convertView.findViewById(R.id.tvUrl);
                Button btnDelete = convertView.findViewById(R.id.btnDelete);
                View viewStatus = convertView.findViewById(R.id.viewStatus);

                tvUrl.setText(url);

                // Cập nhật chấm màu (Đỏ nếu offline hoặc chưa check, Xanh nếu online)
                Boolean isOnline = onlineStatus.get(url);
                if (isOnline != null && isOnline) {
                    viewStatus.setBackgroundResource(R.drawable.circle_green);
                } else {
                    viewStatus.setBackgroundResource(R.drawable.circle_red);
                }
                
                convertView.setOnClickListener(v -> connectToServer(url));
                btnDelete.setOnClickListener(v -> {
                    historyList.remove(position);
                    saveHistory();
                    notifyDataSetChanged();
                });
                return convertView;
            }
        };
        lvHistory.setAdapter(historyAdapter);

        // --- NÚT QUAY LẠI TRANG WEB ---
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (server != null) { server.clearAllData(); server.stop(); server = null; }
            webView.loadUrl("about:blank");
            webLayout.setVisibility(View.GONE);
            selectionLayout.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Đã làm sạch dữ liệu", Toast.LENGTH_SHORT).show();
        });

        // Khởi tạo luồng Ping
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                pingAllServers();
                pingHandler.postDelayed(this, 3000); // 3 giây lặp lại 1 lần
            }
        };
    }

    private void pingAllServers() {
        for (String url : historyList) {
            pingExecutor.execute(() -> {
                boolean isOnline = false;
                try {
                    // Phân tích URL để lấy IP và Port
                    URI uri = URI.create(url);
                    String host = uri.getHost();
                    int port = uri.getPort() == -1 ? 80 : uri.getPort();
                    
                    // Thử kết nối TCP (timeout 2 giây)
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    socket.close();
                    isOnline = true;
                } catch (Exception e) {
                    isOnline = false;
                }

                boolean finalIsOnline = isOnline;
                runOnUiThread(() -> {
                    onlineStatus.put(url, finalIsOnline);
                    historyAdapter.notifyDataSetChanged(); // Vẽ lại giao diện
                });
            });
        }
    }

    private void startHostMode() {
        try {
            server = new FileShareServer(this, PORT);
            server.start();
            selectionLayout.setVisibility(View.GONE);
            webLayout.setVisibility(View.VISIBLE);
            setupWebView();
            webView.loadUrl("http://localhost:" + PORT);
        } catch (IOException e) {
            Toast.makeText(this, "Lỗi khởi động Server", Toast.LENGTH_SHORT).show();
        }
    }

    private void showClientMode() {
        selectionLayout.setVisibility(View.GONE);
        clientLayout.setVisibility(View.VISIBLE);
        pingHandler.post(pingRunnable); // Bắt đầu quét ping
    }

    private void startQrScan() {
        new IntentIntegrator(this).setPrompt("Quét mã QR máy Host").setOrientationLocked(false).initiateScan();
    }

    private void connectToServer(String url) {
        if (!historyList.contains(url)) {
            historyList.add(0, url);
            if (historyList.size() > 10) historyList.remove(10);
            saveHistory();
            historyAdapter.notifyDataSetChanged();
        }

        clientLayout.setVisibility(View.GONE);
        webLayout.setVisibility(View.VISIBLE);
        setupWebView();
        webView.loadUrl(url);
        pingHandler.removeCallbacks(pingRunnable); // Dừng ping khi đã vào Web
    }

    private void loadHistory() {
        String json = prefs.getString("history", "[]");
        try {
            JSONArray array = new JSONArray(json);
            historyList.clear();
            for (int i = 0; i < array.length(); i++) historyList.add(array.getString(i));
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void saveHistory() {
        JSONArray array = new JSONArray();
        for (String url : historyList) array.put(url);
        prefs.edit().putString("history", array.toString()).apply();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try { startActivityForResult(intent, FILE_CHOOSER_REQUEST); } 
                catch (Exception e) { MainActivity.this.filePathCallback = null; return false; }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (filename.endsWith(".bin") || filename.startsWith("download")) {
                    String[] parts = url.split("/");
                    if (parts.length > 0) filename = android.net.Uri.decode(parts[parts.length - 1]);
                }
                request.setTitle(filename);
                request.setDescription("Đang tải file...");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) { dm.enqueue(request); Toast.makeText(this, "Đang tải: " + filename, Toast.LENGTH_SHORT).show(); }
            } catch (Exception e) { Toast.makeText(this, "Lỗi tải file", Toast.LENGTH_LONG).show(); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents();
                if (!scannedUrl.startsWith("http")) scannedUrl = "http://" + scannedUrl;
                if (!scannedUrl.matches(".*:\\d+.*")) scannedUrl += ":8000";
                connectToServer(scannedUrl);
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
        } else { super.onActivityResult(requestCode, resultCode, data); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
        pingHandler.removeCallbacks(pingRunnable);
        pingExecutor.shutdown();
    }
}
