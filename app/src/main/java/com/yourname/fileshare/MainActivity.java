package com.yourname.fileshare;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;
    private static final int FILE_CHOOSER_REQUEST = 1;
    
    private FileShareServer server;
    private WebView webView;
    private LinearLayout selectionLayout, webLayout, clientLayout;
    private ValueCallback<Uri[]> filePathCallback;

    // Lịch sử kết nối
    private List<String> historyList = new ArrayList<>();
    private ArrayAdapter<String> historyAdapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("FileSharePrefs", MODE_PRIVATE);

        webView = findViewById(R.id.webView);
        selectionLayout = findViewById(R.id.selectionLayout);
        webLayout = findViewById(R.id.webLayout);
        clientLayout = findViewById(R.id.clientLayout);

        // --- MÀN HÌNH CHỌN BAN ĐẦU ---
        findViewById(R.id.btnHost).setOnClickListener(v -> startHostMode());
        findViewById(R.id.btnClient).setOnClickListener(v -> showClientMode());

        // --- MÀN HÌNH CLIENT ---
        findViewById(R.id.btnBackToSelection).setOnClickListener(v -> {
            clientLayout.setVisibility(View.GONE);
            selectionLayout.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.btnConnectIp).setOnClickListener(v -> {
            EditText et = findViewById(R.id.etIpAddress);
            String ip = et.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập IP", Toast.LENGTH_SHORT).show();
                return;
            }
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
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.item_history, parent, false);
                }
                String url = getItem(position);
                TextView tvUrl = convertView.findViewById(R.id.tvUrl);
                Button btnDelete = convertView.findViewById(R.id.btnDelete);

                tvUrl.setText(url);
                
                // Click vào dòng để kết nối nhanh
                convertView.setOnClickListener(v -> connectToServer(url));
                
                // Nút xoá
                btnDelete.setOnClickListener(v -> {
                    historyList.remove(position);
                    saveHistory();
                    notifyDataSetChanged();
                });

                return convertView;
            }
        };
        lvHistory.setAdapter(historyAdapter);

        // --- NÚT QUAY LẠI MÀN HÌNH WEB ---
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (server != null) {
                server.clearAllData();
                server.stop();
                server = null;
            }
            webView.loadUrl("about:blank");
            webLayout.setVisibility(View.GONE);
            selectionLayout.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Đã làm sạch dữ liệu", Toast.LENGTH_SHORT).show();
        });
    }

    // --- CÁC HÀM XỬ LÝ CHẾ ĐỘ ---
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
    }

    private void startQrScan() {
        new IntentIntegrator(this).setPrompt("Quét mã QR máy Host").setOrientationLocked(false).initiateScan();
    }

    private void connectToServer(String url) {
        // Lưu vào lịch sử nếu chưa có
        if (!historyList.contains(url)) {
            historyList.add(0, url); // Thêm lên đầu
            if (historyList.size() > 10) historyList.remove(10); // Giới hạn 10 cái
            saveHistory();
            historyAdapter.notifyDataSetChanged();
        }

        clientLayout.setVisibility(View.GONE);
        webLayout.setVisibility(View.VISIBLE);
        setupWebView();
        webView.loadUrl(url);
    }

    // --- HÀM LƯU / TẢI LỊCH SỬ ---
    private void loadHistory() {
        String json = prefs.getString("history", "[]");
        try {
            JSONArray array = new JSONArray(json);
            historyList.clear();
            for (int i = 0; i < array.length(); i++) {
                historyList.add(array.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveHistory() {
        JSONArray array = new JSONArray();
        for (String url : historyList) array.put(url);
        prefs.edit().putString("history", array.toString()).apply();
    }

    // --- CẤU HÌNH WEBVIEW ---
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
    }
}
