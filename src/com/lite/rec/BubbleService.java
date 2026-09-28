package com.lite.rec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Giữ 1 phiên MediaProjection + 1 VirtualDisplay (không gắn Surface khi rảnh => gần như 0 chi phí).
 * Chạm bong bóng: gắn Surface của encoder vào VirtualDisplay để ghi / gỡ ra để dừng.
 * Nhờ vậy giữa trận không bị hiện hộp thoại xin quyền làm game bị tạm dừng.
 */
public class BubbleService extends Service {
    static final String A_START = "com.lite.rec.START";
    static final String A_STOP_REC = "com.lite.rec.STOPREC";
    static final String A_QUIT = "com.lite.rec.QUIT";
    static final String X_CODE = "code";
    static final String X_DATA = "data";
    static final String X_REAUTH = "reauth";

    static volatile boolean running;

    private static final String CH_ID = "rec";
    private static final int NOTIF_ID = 1;
    private static final int READY = 0, REC = 1, DEAD = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Bubble bubble;
    private WindowManager.LayoutParams lp;
    private MediaProjection proj;
    private MediaProjection.Callback projCb;
    private VirtualDisplay vd;
    private Recorder rec;
    private int vw, vh, dpi;
    private int state = DEAD;
    private int finishing;
    private boolean quitting;
    private float density;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        wm = getSystemService(WindowManager.class);
        density = getResources().getDisplayMetrics().density;
        NotificationChannel ch = new NotificationChannel(CH_ID, "Lite Rec", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_START.equals(a)) {
            startSession(intent);
        } else if (A_STOP_REC.equals(a)) {
            stopRec();
        } else if (A_QUIT.equals(a)) {
            shutdown();
        } else if (bubble == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ phiên chiếu màn hình

    @SuppressWarnings("deprecation")
    private void startSession(Intent i) {
        try {
            startForeground(NOTIF_ID, notif(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) {
            toast("Không chạy được foreground service: " + e.getMessage());
            stopSelf();
            return;
        }
        quitting = false;
        stopRec();
        releaseProjection();
        ensureBubble();
        try {
            int code = i.getIntExtra(X_CODE, 0);
            Intent data = i.getParcelableExtra(X_DATA);
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            proj = m.getMediaProjection(code, data);
            projCb = new MediaProjection.Callback() {
                @Override public void onStop() { onProjectionStopped(); }
            };
            proj.registerCallback(projCb, main);   // bắt buộc đăng ký trước createVirtualDisplay (Android 14+)
            computeSize();
            vd = proj.createVirtualDisplay("LiteRec", vw, vh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
            setState(READY);
        } catch (Exception e) {
            toast("Lỗi khởi tạo chiếu màn hình: " + e.getMessage());
            releaseProjection();
            setState(DEAD);
        }
    }

    private void computeSize() {
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        int longSide = Math.max(b.width(), b.height());
        int shortSide = Math.min(b.width(), b.height());
        double sc = longSide > Recorder.LONG_SIDE ? (double) Recorder.LONG_SIDE / longSide : 1.0;
        vw = align16(longSide * sc);   // luôn khung ngang (chơi game ngang)
        vh = align16(shortSide * sc);
        dpi = getResources().getConfiguration().densityDpi;
    }

    private static int align16(double v) { return Math.max(16, (int) Math.round(v / 16.0) * 16); }

    private void onProjectionStopped() {
        stopRec();
        if (vd != null) { vd.release(); vd = null; }
        proj = null;   // hệ thống đã tự dừng, không gọi stop() lại
        if (!quitting) {
            setState(DEAD);
            toast("Đã dừng chia sẻ màn hình. Chạm bong bóng để cấp lại.");
        }
    }

    private void releaseProjection() {
        if (vd != null) { try { vd.release(); } catch (Exception ignored) { } vd = null; }
        if (proj != null) {
            try { if (projCb != null) proj.unregisterCallback(projCb); } catch (Exception ignored) { }
            try { proj.stop(); } catch (Exception ignored) { }
            proj = null;
        }
    }

    // ------------------------------------------------------------ ghi / dừng

    private void startRec() {
        if (proj == null || vd == null || rec != null) return;
        Recorder r = new Recorder(this, proj, vw, vh);
        try {
            Surface s = r.start();
            vd.setSurface(s);
            rec = r;
            setState(REC);
        } catch (Exception e) {
            r.abort();
            toast("Không bắt đầu ghi được: " + e.getMessage());
        }
    }

    private void stopRec() {
        Recorder r = rec;
        if (r == null) return;
        rec = null;
        try { if (vd != null) vd.setSurface(null); } catch (Exception ignored) { }
        finishing++;
        if (!quitting) setState(proj != null ? READY : DEAD);
        r.stopAsync(msg -> main.post(() -> {
            toast(msg);
            finishing--;
            if (quitting && finishing == 0) finishShutdown();
        }));
    }

    private void shutdown() {
        quitting = true;
        stopRec();
        if (finishing == 0) finishShutdown();
    }

    private void finishShutdown() {
        releaseProjection();
        removeBubble();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void onBubbleClick() {
        if (state == DEAD) {
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra(X_REAUTH, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { startActivity(i); } catch (Exception e) { toast("Mở app Lite Rec để cấp lại quyền chiếu màn hình"); }
        } else if (state == READY) {
            startRec();
        } else {
            stopRec();
        }
    }

    // ------------------------------------------------------------ giao diện

    private void setState(int s) {
        state = s;
        if (bubble != null) {
            int size = dp(s == REC ? 34 : 44);
            lp.width = size;
            lp.height = size;
            bubble.st = s;
            try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
            bubble.invalidate();
        }
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, notif(s == REC));
        } catch (Exception ignored) { }
    }

    private Notification notif(boolean recording) {
        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(recording ? "Đang ghi màn hình" : "Lite Rec")
                .setContentText(recording ? "Chạm bong bóng hoặc nút Dừng để lưu" : "Chạm bong bóng để bắt đầu ghi")
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (recording) b.addAction(action("Dừng", A_STOP_REC));
        b.addAction(action("Tắt", A_QUIT));
        return b.build();
    }

    private Notification.Action action(String label, String act) {
        Intent i = new Intent(this, BubbleService.class).setAction(act);
        PendingIntent pi = PendingIntent.getService(this, act.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), label, pi).build();
    }

    private void ensureBubble() {
        if (bubble != null) return;
        try {
            SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);
            bubble = new Bubble(this);
            int size = dp(44);
            lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = sp.getInt("x", dp(12));
            lp.y = sp.getInt("y", dp(140));
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            wm.addView(bubble, lp);
            clampBubble();
        } catch (Exception e) {
            bubble = null;
            toast("Không hiện được bong bóng — kiểm tra quyền 'Hiển thị trên ứng dụng khác'");
        }
    }

    private void removeBubble() {
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
    }

    private void clampBubble() {
        if (bubble == null) return;
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        lp.x = Math.max(0, Math.min(lp.x, b.width() - lp.width));
        lp.y = Math.max(0, Math.min(lp.y, b.height() - lp.height));
        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        clampBubble();
    }

    @Override public void onDestroy() {
        running = false;
        removeBubble();
        releaseProjection();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * density); }

    private void toast(String s) { Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------ bong bóng

    private static final class Bubble extends View {
        final BubbleService svc;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int st = DEAD;
        float dx, dy;
        int ox, oy;
        boolean moved;
        final int slop;

        Bubble(BubbleService s) {
            super(s);
            svc = s;
            slop = ViewConfiguration.get(s).getScaledTouchSlop();
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
        }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            if (st == REC) {
                p.setColor(0xB3E53935);
                c.drawCircle(r, r, r, p);
                p.setColor(0xFFFFFFFF);
                float q = r * 0.4f;
                c.drawRoundRect(r - q, r - q, r + q, r + q, q * 0.25f, q * 0.25f, p);
            } else if (st == READY) {
                p.setColor(0xCC1E1E1E);
                c.drawCircle(r, r, r, p);
                p.setColor(0xFFE53935);
                c.drawCircle(r, r, r * 0.42f, p);
            } else {
                p.setColor(0xDDF59E0B);
                c.drawCircle(r, r, r, p);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(r * 1.3f);
                c.drawText("!", r, r + r * 0.45f, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX();
                    dy = e.getRawY();
                    ox = svc.lp.x;
                    oy = svc.lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                    if (!moved && Math.hypot(mx, my) > slop) moved = true;
                    if (moved) {
                        svc.lp.x = ox + Math.round(mx);
                        svc.lp.y = oy + Math.round(my);
                        try { svc.wm.updateViewLayout(this, svc.lp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) {
                        svc.clampBubble();
                        svc.getSharedPreferences("p", Context.MODE_PRIVATE).edit()
                                .putInt("x", svc.lp.x).putInt("y", svc.lp.y).apply();
                    } else {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                        svc.onBubbleClick();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
