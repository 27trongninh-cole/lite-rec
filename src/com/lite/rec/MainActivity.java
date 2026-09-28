package com.lite.rec;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int RC_PROJ = 1, RC_PERM = 2;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);

        root.addView(btn("1. Cấp quyền hiển thị trên ứng dụng khác", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        root.addView(btn("2. Cấp quyền ghi âm + thông báo", v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, RC_PERM);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RC_PERM);
            }
        }));
        root.addView(btn("3. Bỏ giới hạn pin", v ->
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())))));
        root.addView(btn("4. BẬT bong bóng", v -> requestProjection()));
        root.addView(btn("Tắt bong bóng", v -> {
            if (BubbleService.running) {
                startService(new Intent(this, BubbleService.class).setAction(BubbleService.A_QUIT));
            }
            refresh();
        }));

        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setPadding(0, pad, 0, 0);
        hint.setText("HyperOS: vào Cài đặt > Ứng dụng > Quản lý ứng dụng > Lite Rec:\n"
                + "• Tự khởi chạy: BẬT\n"
                + "• Tiết kiệm pin: Không hạn chế\n"
                + "• Quyền khác: bật \"Hiển thị cửa sổ bật lên khi chạy ở nền\"\n\n"
                + "Cách dùng: bấm BẬT bong bóng, chọn \"Toàn màn hình\", rồi vào game. "
                + "Chạm bong bóng để ghi (máy rung nhẹ, bong bóng sẽ TÀNG HÌNH), chạm đúng vị trí cũ hoặc nút Dừng trong thông báo để dừng — video lưu vào Movies/. Nên đặt bong bóng ở góc không có nút bấm của game.");
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        handle(getIntent());
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i != null && i.getBooleanExtra(BubbleService.X_REAUTH, false)) {
            i.removeExtra(BubbleService.X_REAUTH);
            requestProjection();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean ov = Settings.canDrawOverlays(this);
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean nt = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean bt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        status.setText(mark(ov) + " Hiển thị trên ứng dụng khác\n"
                + mark(mic) + " Ghi âm (bắt âm thanh hệ thống)\n"
                + mark(nt) + " Thông báo\n"
                + mark(bt) + " Không giới hạn pin\n"
                + mark(BubbleService.running) + " Bong bóng đang chạy");
    }

    private static String mark(boolean ok) { return ok ? "✓" : "✗"; }

    private void requestProjection() {
        if (!Settings.canDrawOverlays(this)
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cấp quyền 1 và 2 trước đã", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : m.createScreenCaptureIntent();
        startActivityForResult(i, RC_PROJ);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_PROJ && res == RESULT_OK && data != null) {
            Intent s = new Intent(this, BubbleService.class)
                    .setAction(BubbleService.A_START)
                    .putExtra(BubbleService.X_CODE, res)
                    .putExtra(BubbleService.X_DATA, data);
            startForegroundService(s);
            finish();   // thoát app cho nhẹ, service tự chạy
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        refresh();
    }
}
