package com.personal.tensionhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TensionHomeView extends View {
    private static final int CREAM = Color.rgb(246, 247, 226);
    private static final int INK = Color.rgb(21, 27, 23);
    private static final int ORANGE = Color.rgb(255, 113, 56);
    private static final int PANEL = Color.rgb(241, 242, 220);
    private static final int MUTED = Color.rgb(228, 230, 210);
    private static final int YELLOW = Color.rgb(255, 218, 26);

    private static final String[] DEFAULT_LABELS = {
            "Claude", "YouTube", "Instagram", "Media",
            "Gmail", "Chrome", "LINE", "Work"
    };
    private static final String[] DEFAULT_SECONDARY_LABELS = {
            "", "YouTube Music", "X", "Photos",
            "", "", "", ""
    };
    private static final String[] DEFAULT_SECONDARY_PACKAGES = {
            "", "com.google.android.apps.youtube.music", "com.twitter.android",
            "com.google.android.apps.photos", "", "", "", ""
    };
    private static final String[] DEFAULT_PACKAGES = {
            "com.anthropic.claude", "com.google.android.youtube",
            "com.instagram.android", "com.sec.android.gallery3d",
            "com.google.android.gm", "com.android.chrome",
            "jp.naver.line.android", ""
    };

    private final MainActivity activity;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF volumeRect = new RectF();
    private final RectF playRect = new RectF();
    private final RectF[] tileRects = new RectF[8];
    private final float[] tileScale = new float[8];
    private final float[] tileVelocity = new float[8];
    private final float[] tilePull = new float[8];
    private final float[] knobRotation = new float[8];
    private final float[] knobTarget = new float[8];
    private final SharedPreferences preferences;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> notificationPackages = new HashSet<>();
    private final List<MainActivity.LauncherApp> drawerApps = new ArrayList<>();
    private final List<MainActivity.LauncherApp> allApps = new ArrayList<>();
    private final List<RectF> drawerAppRects = new ArrayList<>();
    private final String[] slotLabels = new String[8];
    private final String[] slotPackages = new String[8];
    private final String[] secondaryLabels = new String[8];
    private final String[] secondaryPackages = new String[8];

    private final int touchSlop;
    private final SimpleDateFormat timeMain = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
    private final SimpleDateFormat timeSeconds = new SimpleDateFormat(":ss", Locale.ENGLISH);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM.dd EEE", Locale.ENGLISH);

    private boolean coverProfile;
    private boolean mediaPlaying;
    private boolean notificationAccessEnabled;
    private boolean drawerOpen;
    private boolean draggingVolume;
    private boolean volumeMoved;
    private boolean longPressTriggered;
    private String mediaTitle = "";
    private String mediaArtist = "";
    private String mediaSource = "Media";
    private String drawerTitle = "ALL APPS";
    private int pressedTile = -1;
    private int activeTile;
    private int pressedDrawerApp = -1;
    private int previousVolume = 7;
    private int currentPage;
    private int pageEnterDirection;
    private int statusBattery = 0;
    private boolean statusWifi;
    private float downX;
    private float downY;
    private float lastTouchX;
    private float lastTouchY;
    private float drawerScroll;
    private float drawerProgress;
    private float drawerVelocity;
    private float phase;
    private long downTime;
    private long lastFrameNanos;
    private long introStartMs;
    private long activeUntilMs;
    private long nextStatusRefreshMs;
    private long pageTransitionStartMs;

    private final Runnable longPressRunnable = () -> {
        if (pressedTile < 0 || draggingVolume || movedTooFar()) return;
        longPressTriggered = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int slot = pressedTile;
        releaseTileSpring();
        activity.showSlotEditor(slot);
    };

    public TensionHomeView(MainActivity context) {
        super(context);
        this.activity = context;
        this.preferences = context.getSharedPreferences("tension_home", Context.MODE_PRIVATE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(CREAM);
        setFocusable(true);
        setClickable(true);
        setHapticFeedbackEnabled(true);

        for (int i = 0; i < 8; i++) {
            tileRects[i] = new RectF();
            tileScale[i] = 1f;
            slotLabels[i] = preferences.getString("slot_label_" + i, DEFAULT_LABELS[i]);
            slotPackages[i] = preferences.getString("slot_package_" + i, DEFAULT_PACKAGES[i]);
            secondaryLabels[i] = preferences.getString("secondary_label_" + i, DEFAULT_SECONDARY_LABELS[i]);
            secondaryPackages[i] = preferences.getString("secondary_package_" + i, DEFAULT_SECONDARY_PACKAGES[i]);
        }
        activeTile = preferences.getInt("active_tile", 4);
        refreshInstalledApps();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = System.nanoTime();
        introStartMs = SystemClock.uptimeMillis();
        pageTransitionStartMs = introStartMs;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float delta = Math.min(0.05f, Math.max(0.001f, (now - lastFrameNanos) / 1_000_000_000f));
        lastFrameNanos = now;
        updateMotion(delta);

        float widthDp = getWidth() / getResources().getDisplayMetrics().density;
        float portraitRatio = getHeight() / (float) Math.max(1, getWidth());
        coverProfile = widthDp < 560f || portraitRatio > 1.48f;
        canvas.drawColor(CREAM);
        drawMediaTypography(canvas);
        drawStatusIndicators(canvas);
        float pageProgress = easeOut(clamp01((SystemClock.uptimeMillis() - pageTransitionStartMs) / 380f));
        canvas.save();
        canvas.translate((1f - pageProgress) * getWidth() * pageEnterDirection, 0f);
        if (currentPage == 1) drawMediaPage(canvas);
        else if (coverProfile) drawCoverHome(canvas);
        else drawInnerHome(canvas);
        canvas.restore();
        if (drawerProgress > 0.002f) drawDrawer(canvas);
        postInvalidateDelayed(mediaPlaying || drawerOpen || drawerProgress > 0.002f ? 16L : 40L);
    }

    private void updateMotion(float delta) {
        phase += delta * (mediaPlaying ? 2.1f : 0.48f);
        for (int i = 0; i < tileScale.length; i++) {
            float intro = introProgress(i * 45L, 460L);
            float target = pressedTile == i && !longPressTriggered ? 0.82f : Math.max(0.05f, intro);
            float acceleration = (target - tileScale[i]) * 52f;
            tileVelocity[i] += acceleration * delta;
            tileVelocity[i] *= (float) Math.pow(0.085, delta);
            tileScale[i] += tileVelocity[i] * delta;
            tileScale[i] = Math.max(0.72f, Math.min(1.16f, tileScale[i]));
            knobRotation[i] += (knobTarget[i] - knobRotation[i]) * Math.min(1f, delta * 12f);
        }

        float drawerTarget = drawerOpen ? 1f : 0f;
        drawerVelocity += (drawerTarget - drawerProgress) * 42f * delta;
        drawerVelocity *= (float) Math.pow(0.06, delta);
        drawerProgress += drawerVelocity * delta;
        if (Math.abs(drawerTarget - drawerProgress) < 0.001f && Math.abs(drawerVelocity) < 0.001f) {
            drawerProgress = drawerTarget;
            drawerVelocity = 0f;
        }
        drawerProgress = Math.max(0f, Math.min(1f, drawerProgress));
    }

    private void drawInnerHome(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float margin = dp(42);

        drawVerticalBrand(canvas, margin + dp(20), dp(118));
        drawPlayControl(canvas, w - margin - dp(20), dp(118), dp(36));

        float gridCenterX = w * 0.54f;
        float gridCenterY = Math.min(h * 0.39f, dp(310));
        drawCalendar(canvas, gridCenterX, gridCenterY, dp(37), dp(30));

        float panelBottom = h - dp(52);
        float panelTop = Math.max(h * 0.67f, panelBottom - dp(242));
        float clockY = panelTop - dp(34);
        drawClock(canvas, margin, clockY, w - margin);

        float volumeWidth = dp(92);
        float panelGap = dp(14);
        volumeRect.set(margin, panelTop, margin + volumeWidth, panelBottom);
        drawVolumeControl(canvas, volumeRect);

        float gridLeft = volumeRect.right + panelGap;
        float tileGap = dp(14);
        float tileWidth = (w - margin - gridLeft - tileGap * 3f) / 4f;
        float tileHeight = (panelBottom - panelTop - tileGap) / 2f;
        for (int i = 0; i < 8; i++) {
            int col = i % 4;
            int row = i / 4;
            RectF rect = tileRects[i];
            rect.set(gridLeft + col * (tileWidth + tileGap),
                    panelTop + row * (tileHeight + tileGap),
                    gridLeft + col * (tileWidth + tileGap) + tileWidth,
                    panelTop + row * (tileHeight + tileGap) + tileHeight);
            drawAppTile(canvas, i, rect, i == 3 || i == 7);
        }
        drawPageIndicator(canvas, w * 0.5f, h - dp(20));
    }

    private void drawCoverHome(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float margin = dp(23);

        setText(dp(23), INK, true, 255);
        drawTrackedText(canvas, "OTER.Y", margin, dp(54), dp(0.9f));
        drawPlayControl(canvas, w - margin - dp(8), dp(108), dp(29));

        float gridY = Math.min(h * 0.25f, dp(205));
        drawCalendar(canvas, w * 0.5f, gridY, dp(31), dp(25));

        float panelTop = Math.max(dp(325), h * 0.43f);
        float panelBottom = h - dp(42);
        drawClock(canvas, margin, panelTop - dp(28), w - margin);

        float volumeWidth = dp(56);
        float gap = dp(10);
        volumeRect.set(margin, panelTop, margin + volumeWidth, panelBottom);
        drawVolumeControl(canvas, volumeRect);

        float gridLeft = volumeRect.right + gap;
        float tileWidth = (w - margin - gridLeft - gap) / 2f;
        float tileHeight = (panelBottom - panelTop - gap * 3f) / 4f;
        for (int i = 0; i < 8; i++) {
            int col = i % 2;
            int row = i / 2;
            RectF rect = tileRects[i];
            rect.set(gridLeft + col * (tileWidth + gap),
                    panelTop + row * (tileHeight + gap),
                    gridLeft + col * (tileWidth + gap) + tileWidth,
                    panelTop + row * (tileHeight + gap) + tileHeight);
            drawAppTile(canvas, i, rect, i == 3 || i == 7);
        }
        drawPageIndicator(canvas, w * 0.5f, h - dp(15));
    }

    private void drawMediaPage(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float margin = coverProfile ? dp(23) : dp(42);
        volumeRect.setEmpty();
        for (int i = 4; i < tileRects.length; i++) tileRects[i].setEmpty();

        setText(coverProfile ? dp(23) : dp(29), INK, true, 255);
        drawTrackedText(canvas, "OTER.Y / 02", margin, coverProfile ? dp(54) : dp(72), dp(0.8f));
        drawPlayControl(canvas, w - margin - dp(8), coverProfile ? dp(108) : dp(118),
                coverProfile ? dp(29) : dp(36));

        float discRadius = coverProfile ? Math.min(w * 0.27f, dp(88)) : Math.min(w * 0.19f, dp(142));
        float discX = coverProfile ? w * 0.5f : w * 0.32f;
        float discY = coverProfile ? h * 0.27f : h * 0.34f;
        paint.setColor(INK);
        paint.setAlpha(255);
        canvas.drawCircle(discX, discY, discRadius, paint);
        paint.setColor(CREAM);
        canvas.drawCircle(discX, discY, discRadius * 0.17f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(ORANGE);
        paint.setAlpha(205);
        RectF orbit = new RectF(discX - discRadius * 1.12f, discY - discRadius * 1.12f,
                discX + discRadius * 1.12f, discY + discRadius * 1.12f);
        canvas.drawArc(orbit, phase * 95f, 238f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        float markerAngle = (float) Math.toRadians(phase * 95f + 238f);
        canvas.drawCircle(discX + (float) Math.cos(markerAngle) * discRadius * 1.12f,
                discY + (float) Math.sin(markerAngle) * discRadius * 1.12f, dp(4), paint);

        if (mediaPlaying && !mediaTitle.isEmpty()) {
            setText(coverProfile ? dp(18) : dp(27), INK, true, 210);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(ellipsize(mediaTitle, w - margin * 2), w * 0.5f,
                    discY + discRadius + (coverProfile ? dp(45) : dp(62)), paint);
            setText(coverProfile ? dp(9) : dp(12), INK, false, 72);
            canvas.drawText(ellipsize(mediaArtist, w - margin * 2), w * 0.5f,
                    discY + discRadius + (coverProfile ? dp(66) : dp(88)), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        float panelTop = coverProfile ? h * 0.52f : h * 0.63f;
        float panelBottom = h - (coverProfile ? dp(42) : dp(52));
        int columns = coverProfile ? 2 : 4;
        float gap = coverProfile ? dp(10) : dp(14);
        float tileWidth = (w - margin * 2 - gap * (columns - 1)) / columns;
        float tileHeight = coverProfile ? (panelBottom - panelTop - gap) / 2f : panelBottom - panelTop;
        for (int i = 0; i < 4; i++) {
            int col = i % columns;
            int row = i / columns;
            RectF rect = tileRects[i];
            rect.set(margin + col * (tileWidth + gap), panelTop + row * (tileHeight + gap),
                    margin + col * (tileWidth + gap) + tileWidth,
                    panelTop + row * (tileHeight + gap) + tileHeight);
            drawAppTile(canvas, i, rect, false);
        }
        drawPageIndicator(canvas, w * 0.5f, h - (coverProfile ? dp(15) : dp(20)));
    }

    private void drawVerticalBrand(Canvas canvas, float x, float y) {
        canvas.save();
        canvas.rotate(90f, x, y);
        setText(dp(28), INK, true, 255);
        drawTrackedText(canvas, "OTER.Y", x, y, dp(1.1f));
        canvas.restore();
    }

    private void drawStatusIndicators(Canvas canvas) {
        float right = getWidth() - (coverProfile ? dp(20) : dp(39));
        float y = dp(24);
        long now = SystemClock.uptimeMillis();
        if (now >= nextStatusRefreshMs) {
            BatteryManager batteryManager = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
            statusBattery = batteryManager == null ? 0
                    : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            ConnectivityManager connectivity = (ConnectivityManager) getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities capabilities = connectivity == null ? null
                    : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            statusWifi = capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            nextStatusRefreshMs = now + 5000L;
        }
        int battery = statusBattery;
        boolean wifi = statusWifi;

        // Four independently spaced groups: cellular, Wi-Fi, percentage, battery.
        // The fixed right-edge offsets keep them from colliding on either Fold8 display.
        setText(coverProfile ? dp(7.2f) : dp(8.5f), INK, false, 118);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Math.max(0, battery) + "%", right - dp(31), y + dp(2.5f), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(INK);
        paint.setAlpha(118);
        RectF batteryRect = new RectF(right - dp(23), y - dp(7), right - dp(3), y + dp(7));
        canvas.drawRoundRect(batteryRect, dp(2.8f), dp(2.8f), paint);
        canvas.drawLine(right - dp(1.5f), y - dp(3), right - dp(1.5f), y + dp(3), paint);
        paint.setStyle(Paint.Style.FILL);
        float level = Math.max(0f, Math.min(1f, battery / 100f));
        canvas.drawRoundRect(batteryRect.left + dp(2.5f), batteryRect.top + dp(2.5f),
                batteryRect.left + dp(2.5f) + (batteryRect.width() - dp(5)) * level,
                batteryRect.bottom - dp(2.5f), dp(1.2f), dp(1.2f), paint);

        float wifiX = right - dp(61);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.45f));
        paint.setAlpha(wifi ? 118 : 28);
        canvas.drawArc(wifiX - dp(8), y - dp(7), wifiX + dp(8), y + dp(9), 220, 100, false, paint);
        canvas.drawArc(wifiX - dp(5), y - dp(3), wifiX + dp(5), y + dp(7), 220, 100, false, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(wifiX, y + dp(5), dp(1.4f), paint);

        float cellX = right - dp(91);
        paint.setAlpha(118);
        for (int i = 0; i < 4; i++) {
            canvas.drawRoundRect(cellX + i * dp(3.7f), y + dp(5) - dp(3 + i * 2),
                    cellX + i * dp(3.7f) + dp(2.1f), y + dp(6), dp(0.6f), dp(0.6f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMediaTypography(Canvas canvas) {
        if (!mediaPlaying || mediaTitle.isEmpty()) return;
        float w = getWidth();
        float h = getHeight();
        float textSize = coverProfile ? dp(60) : dp(96);
        setText(textSize, INK, true, 14);
        float width = paint.measureText(mediaTitle);
        float travel = w + width + dp(120);
        float x = w - ((phase * dp(36)) % travel);
        float baseline = coverProfile ? h * 0.42f : h * 0.59f;
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.drawText(mediaTitle.toUpperCase(Locale.getDefault()), x, baseline, paint);
        canvas.drawText(mediaTitle.toUpperCase(Locale.getDefault()), x + travel, baseline, paint);
        canvas.restore();
    }

    private void drawPlayControl(Canvas canvas, float centerX, float centerY, float size) {
        playRect.set(centerX - size, centerY - size, centerX + size, centerY + size);
        boolean showMedia = mediaPlaying && !mediaTitle.isEmpty();
        if (showMedia) {
            drawSoundWave(canvas, centerX - size * 0.52f, centerY,
                    size * 0.64f, size * 0.62f);
        }
        if (mediaPlaying) {
            paint.setColor(INK);
            paint.setAlpha(255);
            float barW = size * 0.22f;
            float barH = size * 0.62f;
            canvas.drawRoundRect(centerX - barW * 1.5f, centerY - barH * 0.5f,
                    centerX - barW * 0.5f, centerY + barH * 0.5f, barW * 0.25f, barW * 0.25f, paint);
            canvas.drawRoundRect(centerX + barW * 0.5f, centerY - barH * 0.5f,
                    centerX + barW * 1.5f, centerY + barH * 0.5f, barW * 0.25f, barW * 0.25f, paint);
        } else {
            path.reset();
            path.moveTo(centerX - size * 0.28f, centerY - size * 0.42f);
            path.lineTo(centerX + size * 0.45f, centerY);
            path.lineTo(centerX - size * 0.28f, centerY + size * 0.42f);
            path.close();
            paint.setColor(INK);
            paint.setAlpha(255);
            canvas.drawPath(path, paint);
        }

        if (showMedia) {
            setText(coverProfile ? dp(9f) : dp(11), INK, false, 42);
            paint.setTextAlign(Paint.Align.RIGHT);
            float max = getWidth() - (coverProfile ? dp(46) : dp(84));
            canvas.drawText(ellipsize(mediaTitle, max), centerX + size * 0.35f,
                    centerY - size * 1.25f, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSoundWave(Canvas canvas, float right, float centerY, float width, float height) {
        int columns = coverProfile ? 5 : 6;
        float gapX = width / Math.max(1, columns - 1);
        paint.setColor(INK);
        paint.setAlpha(255);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(coverProfile ? dp(1.8f) : dp(2.2f));
        for (int col = 0; col < columns; col++) {
            float moving = (float) Math.abs(Math.sin(phase * 4.4f + col * 0.91f));
            float lineHeight = height * (0.18f + moving * 0.82f);
            float x = right - width + col * gapX;
            canvas.drawLine(x, centerY - lineHeight * 0.5f,
                    x, centerY + lineHeight * 0.5f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawCalendar(Canvas canvas, float centerX, float centerY, float dx, float dy) {
        Calendar calendar = Calendar.getInstance();
        int today = calendar.get(Calendar.DAY_OF_MONTH);
        int maximum = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        int firstColumn = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int totalCells = 42;
        float startX = centerX - 3f * dx;
        float startY = centerY - 2.5f * dy;
        long elapsed = SystemClock.uptimeMillis() - introStartMs;
        for (int cell = 0; cell < totalCells; cell++) {
            int day = cell - firstColumn + 1;
            boolean inMonth = day >= 1 && day <= maximum;
            int color = !inMonth ? MUTED : day == today ? ORANGE : INK;
            float reveal = clamp01((elapsed - cell * 14L) / 300f);
            float pulse = day == today ? 1f + 0.11f * (float) Math.sin(phase * 8f) : 1f;
            paint.setColor(color);
            paint.setAlpha(Math.round((inMonth ? 255 : 125) * reveal));
            canvas.drawCircle(startX + (cell % 7) * dx, startY + (cell / 7) * dy,
                    dp(6.1f) * pulse * Math.max(0.15f, reveal), paint);
        }
    }

    private void drawClock(Canvas canvas, float left, float y, float right) {
        Date now = new Date();
        String main = timeMain.format(now);
        String seconds = timeSeconds.format(now);
        setText(coverProfile ? dp(22) : dp(27), INK, true, 255);
        float mainTracking = coverProfile ? dp(0.15f) : dp(0.3f);
        drawTrackedText(canvas, main, left, y, mainTracking);
        float mainWidth = trackedWidth(main, mainTracking);
        setText(coverProfile ? dp(16) : dp(21), INK, false, 42);
        canvas.drawText(seconds, left + mainWidth + dp(3), y, paint);

        String date = dateFormat.format(now).toUpperCase(Locale.ENGLISH);
        setText(coverProfile ? dp(15) : dp(21), INK, false, 38);
        drawTrackedTextRight(canvas, date, right, y, coverProfile ? dp(0.45f) : dp(0.7f));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawVolumeControl(Canvas canvas, RectF rect) {
        paint.setColor(PANEL);
        paint.setAlpha(82);
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);

        String source = mediaSource == null || mediaSource.isEmpty() ? "Media" : mediaSource;
        setText(coverProfile ? dp(7.5f) : dp(9), INK, false, 30);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ellipsize(source, rect.width() - dp(8)), rect.centerX(), rect.top + dp(17), paint);

        float trackTop = rect.top + dp(34);
        float trackBottom = rect.bottom - dp(31);
        paint.setColor(INK);
        paint.setAlpha(230);
        paint.setStrokeWidth(dp(1.7f));
        canvas.drawLine(rect.centerX(), trackTop, rect.centerX(), trackBottom, paint);

        int max = audioManager == null ? 15 : Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager == null ? 7 : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float ratio = current / (float) max;
        ratio *= easeOut(introProgress(120L, 520L));
        float knobY = trackBottom - ratio * (trackBottom - trackTop);
        RectF knob = new RectF(rect.centerX() - dp(18), knobY - dp(8),
                rect.centerX() + dp(18), knobY + dp(8));
        paint.setColor(INK);
        paint.setAlpha(255);
        canvas.drawRoundRect(knob, dp(3), dp(3), paint);
        paint.setColor(ORANGE);
        paint.setStrokeWidth(dp(2.2f));
        canvas.drawLine(knob.left + dp(7), knobY, knob.right - dp(7), knobY, paint);

        setText(coverProfile ? dp(7) : dp(8), INK, false, 30);
        canvas.drawText("VOLUME", rect.centerX(), rect.bottom - dp(11), paint);
        setText(coverProfile ? dp(6.3f) : dp(7.2f), INK, false, 38);
        canvas.drawText("Spotify ↑", rect.centerX(), rect.top + dp(28), paint);
        canvas.drawText("카메라 ↓", rect.centerX(), rect.bottom - dp(2), paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawAppTile(Canvas canvas, int index, RectF rect, boolean circle) {
        float scale = tileScale[index];
        float pull = tilePull[index];
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), rect.centerY());

        paint.setColor(PANEL);
        paint.setAlpha(68);
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);

        float labelHeight = coverProfile ? dp(16) : dp(22);
        float padding = coverProfile ? dp(7) : dp(10);
        float available = Math.min(rect.width() - padding * 2, rect.height() - labelHeight - padding * 1.5f);
        float iconSize = Math.max(dp(25), available);
        RectF icon = new RectF(rect.centerX() - iconSize * 0.5f,
                rect.top + padding,
                rect.centerX() + iconSize * 0.5f,
                rect.top + padding + iconSize);
        if (icon.bottom > rect.bottom - labelHeight) icon.offset(0, rect.bottom - labelHeight - icon.bottom);
        icon.offset(0, pull * Math.min(dp(18), rect.height() * 0.18f));

        boolean hasNotification = notificationPackages.contains(slotPackages[index]);
        boolean transientActive = index == activeTile && SystemClock.uptimeMillis() < activeUntilMs;
        paint.setColor(hasNotification || transientActive ? ORANGE : INK);
        paint.setAlpha(255);
        if (circle) {
            canvas.drawCircle(icon.centerX(), icon.centerY(), icon.width() * 0.5f, paint);
        } else {
            canvas.drawRoundRect(icon, icon.width() * 0.18f, icon.width() * 0.18f, paint);
        }

        if (circle) {
            float angle = (float) Math.toRadians(knobRotation[index] - 55f + introProgress(index * 45L, 500L) * 55f);
            float radius = icon.width() * 0.36f;
            paint.setColor(YELLOW);
            paint.setAlpha(255);
            canvas.drawCircle(icon.centerX() + (float) Math.cos(angle) * radius,
                    icon.centerY() + (float) Math.sin(angle) * radius, dp(3), paint);
        } else if (hasNotification || !secondaryPackages[index].isEmpty()) {
            paint.setColor(hasNotification ? Color.WHITE : YELLOW);
            paint.setAlpha(255);
            canvas.drawCircle(icon.right - dp(5), icon.top + dp(5), dp(3), paint);
        }

        setText(coverProfile ? dp(8.2f) : dp(10.5f), INK, false, 205);
        paint.setTextAlign(Paint.Align.CENTER);
        String shownLabel = pull > 0.38f && !secondaryLabels[index].isEmpty()
                ? secondaryLabels[index] : slotLabels[index];
        canvas.drawText(ellipsize(shownLabel, rect.width() - dp(5)),
                rect.centerX(), rect.bottom - dp(5), paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawPageIndicator(Canvas canvas, float x, float y) {
        paint.setColor(currentPage == 0 ? INK : MUTED);
        paint.setAlpha(255);
        canvas.drawCircle(x - dp(4), y, dp(3.3f), paint);
        paint.setColor(currentPage == 1 ? INK : MUTED);
        paint.setAlpha(255);
        canvas.drawCircle(x + dp(12), y, currentPage == 1 ? dp(3.3f) : dp(2.2f), paint);
    }

    private void drawDrawer(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float top = (1f - easeOut(drawerProgress)) * h;
        canvas.save();
        canvas.translate(0, top);
        paint.setColor(CREAM);
        paint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, paint);

        float margin = coverProfile ? dp(22) : dp(42);
        setText(coverProfile ? dp(26) : dp(34), INK, true, 255);
        canvas.drawText(drawerTitle, margin, dp(56), paint);
        setText(dp(9), INK, false, 50);
        canvas.drawText("아래로 쓸어 홈으로 돌아가기", margin, dp(76), paint);
        paint.setColor(ORANGE);
        paint.setAlpha(255);
        canvas.drawCircle(w - margin, dp(50), dp(6), paint);
        drawDrawerSpinner(canvas, w - margin, dp(50));

        drawerAppRects.clear();
        int columns = coverProfile ? 3 : 5;
        float gap = coverProfile ? dp(10) : dp(15);
        float cellWidth = (w - margin * 2 - gap * (columns - 1)) / columns;
        float cellHeight = coverProfile ? dp(94) : dp(112);
        float startY = dp(105) - drawerScroll;
        for (int i = 0; i < drawerApps.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            RectF cell = new RectF(margin + col * (cellWidth + gap),
                    startY + row * (cellHeight + gap),
                    margin + col * (cellWidth + gap) + cellWidth,
                    startY + row * (cellHeight + gap) + cellHeight);
            float itemProgress = clamp01((drawerProgress - i * 0.018f) / 0.82f);
            cell.offset(0, (1f - easeOut(itemProgress)) * dp(54));
            drawerAppRects.add(cell);
            if (cell.bottom < dp(88) || cell.top > h) continue;
            drawDrawerApp(canvas, i, cell);
        }
        canvas.restore();
    }

    private void drawDrawerSpinner(Canvas canvas, float centerX, float centerY) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(INK);
        paint.setAlpha(92);
        RectF ring = new RectF(centerX - dp(13), centerY - dp(13), centerX + dp(13), centerY + dp(13));
        canvas.drawArc(ring, phase * 120f, 235f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        float angle = (float) Math.toRadians(phase * 120f + 235f);
        canvas.drawCircle(centerX + (float) Math.cos(angle) * dp(13),
                centerY + (float) Math.sin(angle) * dp(13), dp(2.4f), paint);
    }

    private void drawDrawerApp(Canvas canvas, int index, RectF cell) {
        MainActivity.LauncherApp app = drawerApps.get(index);
        float pressedScale = index == pressedDrawerApp ? 0.86f : 1f;
        canvas.save();
        canvas.scale(pressedScale, pressedScale, cell.centerX(), cell.centerY());
        float iconSize = Math.min(cell.width() * 0.64f, cell.height() * 0.58f);
        RectF icon = new RectF(cell.centerX() - iconSize / 2,
                cell.top + dp(6), cell.centerX() + iconSize / 2,
                cell.top + dp(6) + iconSize);
        boolean hasNotification = notificationPackages.contains(app.packageName);
        paint.setColor(hasNotification ? ORANGE : INK);
        paint.setAlpha(255);
        canvas.drawRoundRect(icon, iconSize * 0.18f, iconSize * 0.18f, paint);
        if (hasNotification) {
            paint.setColor(Color.WHITE);
            canvas.drawCircle(icon.right - dp(4), icon.top + dp(4), dp(3), paint);
        }
        setText(coverProfile ? dp(8) : dp(10), INK, false, 210);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ellipsize(app.label, cell.width()), cell.centerX(), cell.bottom - dp(8), paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastTouchX = x;
                lastTouchY = y;
                downTime = SystemClock.uptimeMillis();
                longPressTriggered = false;
                if (drawerProgress > 0.5f) {
                    pressedDrawerApp = findDrawerApp(x, y);
                    invalidate();
                    return true;
                }
                if (volumeRect.contains(x, y)) {
                    draggingVolume = true;
                    volumeMoved = false;
                    return true;
                }
                pressedTile = findTile(x, y);
                if (pressedTile >= 0 && pressedTile != 7) {
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (draggingVolume) {
                    if (Math.abs(y - downY) > touchSlop) volumeMoved = true;
                    if (volumeMoved) updateVolumeFromTouch(y);
                    lastTouchY = y;
                    return true;
                }
                if (drawerProgress > 0.5f) {
                    float delta = lastTouchY - y;
                    if (Math.abs(y - downY) > touchSlop) pressedDrawerApp = -1;
                    drawerScroll = clampDrawerScroll(drawerScroll + delta);
                    lastTouchY = y;
                    return true;
                }
                lastTouchX = x;
                lastTouchY = y;
                if (pressedTile >= 0 && pressedTile != 3 && pressedTile != 7
                        && y > downY && !secondaryPackages[pressedTile].isEmpty()) {
                    tilePull[pressedTile] = clamp01((y - downY) / Math.max(dp(54), tileRects[pressedTile].height() * 0.72f));
                    handler.removeCallbacks(longPressRunnable);
                    invalidate();
                    return true;
                }
                if (movedTooFar()) {
                    handler.removeCallbacks(longPressRunnable);
                    if (pressedTile >= 0) releaseTileSpring();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (draggingVolume) {
                    draggingVolume = false;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (!volumeMoved) {
                        toggleMute();
                    } else if (y < volumeRect.top - dp(12)) {
                        activity.launchPackage("com.spotify.music");
                    } else if (y > volumeRect.bottom + dp(12)) {
                        activity.launchCamera();
                    }
                    return true;
                }
                if (drawerProgress > 0.5f) {
                    float swipe = y - downY;
                    int appIndex = pressedDrawerApp;
                    pressedDrawerApp = -1;
                    if (swipe > dp(85) && SystemClock.uptimeMillis() - downTime < 700) {
                        closeDrawer();
                    } else if (appIndex >= 0 && appIndex == findDrawerApp(x, y)
                            && Math.abs(swipe) < touchSlop) {
                        activity.launchPackage(drawerApps.get(appIndex).packageName);
                    }
                    return true;
                }

                float verticalSwipe = y - downY;
                float horizontalSwipe = x - downX;
                int tile = pressedTile;
                float releasedPull = tile >= 0 ? tilePull[tile] : 0f;
                boolean wasLong = longPressTriggered;
                releaseTileSpring();
                if (!wasLong && Math.abs(horizontalSwipe) > dp(85)
                        && Math.abs(verticalSwipe) < dp(65)) {
                    int nextPage = horizontalSwipe < 0 ? 1 : 0;
                    if (nextPage != currentPage) {
                        currentPage = nextPage;
                        pageEnterDirection = horizontalSwipe < 0 ? 1 : -1;
                        pageTransitionStartMs = SystemClock.uptimeMillis();
                        introStartMs = pageTransitionStartMs;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    return true;
                }
                if (verticalSwipe < -dp(85) && SystemClock.uptimeMillis() - downTime < 700) {
                    openDrawer();
                    return true;
                }
                if (!wasLong && playRect.contains(x, y) && playRect.contains(downX, downY)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    activity.togglePlayback();
                    return true;
                }
                if (!wasLong && tile >= 0 && releasedPull > 0.55f
                        && !secondaryPackages[tile].isEmpty()) {
                    String packageName = secondaryPackages[tile];
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    handler.postDelayed(() -> activity.launchPackage(packageName), 90);
                    return true;
                }
                if (!wasLong && tile >= 0 && tile == findTile(x, y)
                        && Math.abs(x - downX) < dp(30) && Math.abs(y - downY) < dp(30)) {
                    tileVelocity[tile] += 5.5f;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    activeTile = tile;
                    activeUntilMs = SystemClock.uptimeMillis() + 420L;
                    if (tile == 3 || tile == 7 || slotPackages[tile].isEmpty()) {
                        knobTarget[tile] += 360f;
                        handler.postDelayed(() -> openDrawer(tile), 150);
                    } else {
                        String packageName = slotPackages[tile];
                        handler.postDelayed(() -> activity.launchPackage(packageName), 135);
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean movedTooFar() {
        return Math.abs(lastTouchX - downX) > touchSlop || Math.abs(lastTouchY - downY) > touchSlop;
    }

    private int findTile(float x, float y) {
        for (int i = 0; i < tileRects.length; i++) {
            if (tileRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    private int findDrawerApp(float x, float y) {
        float translatedY = y - (1f - easeOut(drawerProgress)) * getHeight();
        for (int i = 0; i < drawerAppRects.size(); i++) {
            if (drawerAppRects.get(i).contains(x, translatedY)) return i;
        }
        return -1;
    }

    private void updateVolumeFromTouch(float y) {
        if (audioManager == null) return;
        float trackTop = volumeRect.top + dp(34);
        float trackBottom = volumeRect.bottom - dp(31);
        float ratio = 1f - Math.max(0f, Math.min(1f, (y - trackTop) / Math.max(1f, trackBottom - trackTop)));
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int volume = Math.round(ratio * max);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    private void toggleMute() {
        if (audioManager == null) return;
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > 0) {
            previousVolume = current;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        } else {
            int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.max(1, Math.min(previousVolume, max)), 0);
        }
    }

    private float clampDrawerScroll(float value) {
        int columns = coverProfile ? 3 : 5;
        int rows = (int) Math.ceil(drawerApps.size() / (float) columns);
        float cellHeight = coverProfile ? dp(94) : dp(112);
        float gap = coverProfile ? dp(10) : dp(15);
        float content = rows * (cellHeight + gap) + dp(125);
        return Math.max(0f, Math.min(Math.max(0f, content - getHeight()), value));
    }

    private void releaseTileSpring() {
        if (pressedTile >= 0) {
            tileVelocity[pressedTile] += 3.8f;
            tilePull[pressedTile] = 0f;
        }
        pressedTile = -1;
    }

    private void openDrawer() {
        openDrawer(-1);
    }

    private void openDrawer(int folderIndex) {
        drawerOpen = true;
        drawerVelocity += 1.5f;
        filterDrawer(folderIndex);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawerVelocity -= 1.2f;
        pressedDrawerApp = -1;
    }

    boolean closeOverlay() {
        if (drawerOpen || drawerProgress > 0.05f) {
            closeDrawer();
            return true;
        }
        return false;
    }

    void returnToHomeSurface() {
        closeDrawer();
    }

    void onWindowProfileChanged() {
        drawerScroll = 0f;
        introStartMs = SystemClock.uptimeMillis();
        requestLayout();
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            introStartMs = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    void refreshInstalledApps() {
        allApps.clear();
        allApps.addAll(activity.loadLauncherApps());
        filterDrawer(-1);
    }

    private void filterDrawer(int folderIndex) {
        drawerApps.clear();
        drawerTitle = folderIndex == 3 ? "Media" : folderIndex == 7 ? "Work" : "ALL APPS";
        for (MainActivity.LauncherApp app : allApps) {
            String search = (app.label + " " + app.packageName).toLowerCase(Locale.ROOT);
            boolean media = containsAny(search, "youtube", "spotify", "music", "gallery", "photo",
                    "camera", "netflix", "podcast", "media");
            boolean work = containsAny(search, "gmail", "drive", "docs", "sheets", "calendar", "slack",
                    "teams", "zoom", "notion", "office", "claude", "chrome", "line");
            if (folderIndex < 0 || (folderIndex == 3 && media) || (folderIndex == 7 && work)) {
                drawerApps.add(app);
            }
        }
        if (drawerApps.isEmpty()) drawerApps.addAll(allApps);
        drawerScroll = 0f;
        drawerScroll = clampDrawerScroll(drawerScroll);
        invalidate();
    }

    private boolean containsAny(String source, String... values) {
        for (String value : values) if (source.contains(value)) return true;
        return false;
    }

    void bindSlot(int index, String label, String packageName, boolean secondary) {
        if (index < 0 || index >= slotLabels.length) return;
        if (secondary) {
            secondaryLabels[index] = label;
            secondaryPackages[index] = packageName;
        } else {
            slotLabels[index] = label;
            slotPackages[index] = packageName;
        }
        persistSlot(index);
        tileVelocity[index] += 5f;
        invalidate();
    }

    void clearSecondarySlot(int index) {
        if (index < 0 || index >= slotLabels.length) return;
        secondaryLabels[index] = "";
        secondaryPackages[index] = "";
        persistSlot(index);
        invalidate();
    }

    CharSequence[] getSlotLabels() {
        CharSequence[] labels = new CharSequence[slotLabels.length];
        for (int i = 0; i < labels.length; i++) labels[i] = (i + 1) + ". " + slotLabels[i];
        return labels;
    }

    void swapSlots(int first, int second) {
        if (first < 0 || second < 0 || first >= slotLabels.length || second >= slotLabels.length || first == second) return;
        String label = slotLabels[first];
        String packageName = slotPackages[first];
        String secondaryLabel = secondaryLabels[first];
        String secondaryPackage = secondaryPackages[first];
        slotLabels[first] = slotLabels[second];
        slotPackages[first] = slotPackages[second];
        secondaryLabels[first] = secondaryLabels[second];
        secondaryPackages[first] = secondaryPackages[second];
        slotLabels[second] = label;
        slotPackages[second] = packageName;
        secondaryLabels[second] = secondaryLabel;
        secondaryPackages[second] = secondaryPackage;
        persistSlot(first);
        persistSlot(second);
        tileVelocity[first] += 4f;
        tileVelocity[second] += 4f;
        invalidate();
    }

    private void persistSlot(int index) {
        preferences.edit()
                .putString("slot_label_" + index, slotLabels[index])
                .putString("slot_package_" + index, slotPackages[index])
                .putString("secondary_label_" + index, secondaryLabels[index])
                .putString("secondary_package_" + index, secondaryPackages[index])
                .apply();
    }

    void setMediaState(String title, String artist, String source, boolean playing) {
        mediaTitle = title == null ? "" : title;
        mediaArtist = artist == null ? "" : artist;
        mediaSource = source == null || source.isEmpty() ? "Media" : source;
        mediaPlaying = playing;
        invalidate();
    }

    void setNotificationPackages(String[] packages) {
        notificationPackages.clear();
        if (packages != null) notificationPackages.addAll(Arrays.asList(packages));
        invalidate();
    }

    void setNotificationAccessEnabled(boolean enabled) {
        notificationAccessEnabled = enabled;
    }

    private void setText(float size, int color, boolean bold, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1f);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTrackedText(Canvas canvas, String value, float x, float baseline, float tracking) {
        float cursor = x;
        for (int i = 0; i < value.length(); i++) {
            String letter = value.substring(i, i + 1);
            canvas.drawText(letter, cursor, baseline, paint);
            cursor += paint.measureText(letter) + tracking;
        }
    }

    private void drawTrackedTextRight(Canvas canvas, String value, float right, float baseline, float tracking) {
        drawTrackedText(canvas, value, right - trackedWidth(value, tracking), baseline, tracking);
    }

    private float trackedWidth(String value, float tracking) {
        if (value.isEmpty()) return 0f;
        float width = 0f;
        for (int i = 0; i < value.length(); i++) {
            width += paint.measureText(value.substring(i, i + 1));
        }
        return width + tracking * (value.length() - 1);
    }

    private String ellipsize(String value, float width) {
        if (value == null) return "";
        if (paint.measureText(value) <= width) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 1 && paint.measureText(value, 0, end) + paint.measureText(ellipsis) > width) end--;
        return value.substring(0, Math.max(1, end)) + ellipsis;
    }

    private float easeOut(float value) {
        float x = 1f - value;
        return 1f - x * x * x;
    }

    private float introProgress(long delayMs, long durationMs) {
        return clamp01((SystemClock.uptimeMillis() - introStartMs - delayMs) / (float) durationMs);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
