package com.yourname.fileshare;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 8000;

    private FileShareServer server;
    private TextView tvStatus, tvUrl;
    private Button btnStartStop;
    private ImageView ivQr;

    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvUrl = findViewById(R.id.tvUrl);
        btnStartStop = findViewById(R.id.btnStartStop);
        ivQr = findViewById(R.id.ivQr);

        btnStartStop.setOnClickListener(v -> {
            if (isRunning) stopServer();
            else startServer();
        });

        startServer();
    }

    private void startServer() {
        if (isRunning) return;

        try {
            server = new FileShareServer(this, PORT);
            server.start();
            isRunning = true;

            String ip = NetworkUtils.getLocalIpAddress();
            String url = "http://" + ip + ":" + PORT;

            tvStatus.setText("🟢 Server đang chạy");
            tvUrl.setText(url);
            btnStartStop.setText("⏹ Dừng server");

            renderQR(url);

            Toast.makeText(this, "Server: " + url, Toast.LENGTH_LONG).show();

        } catch (IOException e) {
            e.printStackTrace();
            tvStatus.setText("🔴 Lỗi: " + e.getMessage());
            isRunning = false;
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        isRunning = false;
        tvStatus.setText("🔴 Server đã dừng");
        tvUrl.setText("-");
        btnStartStop.setText("▶ Bắt đầu server");
        ivQr.setImageBitmap(null);
    }

    private void renderQR(String text) {
        int size = 600;
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ivQr.setImageBitmap(bmp);
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
