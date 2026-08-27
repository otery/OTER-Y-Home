package com.personal.tensionhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    static final int CREAM = Color.rgb(246, 247, 226);
    private static final int HOME_ROLE_REQUEST = 41;

    private TensionHomeView homeView;
    private boolean receiverRegistered;

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (homeView == null) return;
            String title = intent.getStringExtra(PlaybackNotificationListener.EXTRA_TITLE);
            String artist = intent.getStringExtra(PlaybackNotificationListener.EXTRA_ARTIST);
            String source = intent.getStringExtra(PlaybackNotificationListener.EXTRA_SOURCE);
            boolean playing = intent.getBooleanExtra(PlaybackNotificationListener.EXTRA_PLAYING, false);
            String[] packages = intent.getStringArrayExtra(PlaybackNotificationListener.EXTRA_NOTIFICATION_PACKAGES);
            homeView.setMediaState(title, artist, source, playing);
            homeView.setNotificationPackages(packages);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        homeView = new TensionHomeView(this);
        setContentView(homeView);
        registerMediaReceiver();

        homeView.postDelayed(() -> {
            requestHomeRoleIfNeeded();
            if (!isNotificationListenerEnabled()) showNotificationAccessExplanation();
        }, 550);
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(CREAM);
        window.setNavigationBarColor(CREAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void registerMediaReceiver() {
        IntentFilter filter = new IntentFilter(PlaybackNotificationListener.ACTION_MEDIA_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (homeView != null) {
            homeView.refreshInstalledApps();
            homeView.setNotificationAccessEnabled(isNotificationListenerEnabled());
        }
        PlaybackNotificationListener.requestSnapshot(this);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(mediaReceiver);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureSystemBars();
        if (homeView != null) homeView.onWindowProfileChanged();
    }

    @Override
    public void onBackPressed() {
        if (homeView != null && homeView.closeOverlay()) return;
        // A launcher should stay on the home surface instead of closing itself.
        if (homeView != null) homeView.returnToHomeSurface();
    }

    void togglePlayback() {
        if (!PlaybackNotificationListener.togglePlayback(this)) {
            Toast.makeText(this, "재생 중인 미디어가 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    void launchPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(this, "앱을 찾을 수 없습니다. 길게 눌러 다른 앱을 지정하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(this, "앱을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    void showSlotEditor(int slotIndex) {
        String[] actions = {"기본 앱 변경", "아래로 당길 앱 변경", "타일 위치 바꾸기", "보조 앱 해제"};
        new AlertDialog.Builder(this)
                .setTitle("OTER.Y 패드 설정")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showAppPicker(slotIndex, false);
                    else if (which == 1) showAppPicker(slotIndex, true);
                    else if (which == 2) showSlotMovePicker(slotIndex);
                    else homeView.clearSecondarySlot(slotIndex);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showSlotMovePicker(int sourceIndex) {
        CharSequence[] destinations = homeView.getSlotLabels();
        new AlertDialog.Builder(this)
                .setTitle("바꿀 위치 선택")
                .setItems(destinations, (dialog, which) -> homeView.swapSlots(sourceIndex, which))
                .setNegativeButton("취소", null)
                .show();
    }

    void showAppPicker(int slotIndex, boolean secondary) {
        List<LauncherApp> apps = loadLauncherApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "선택할 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("이 타일에 연결할 앱")
                .setItems(labels, (dialog, which) -> {
                    LauncherApp selected = apps.get(which);
                    homeView.bindSlot(slotIndex, selected.label, selected.packageName, secondary);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    void launchCamera() {
        try {
            Intent camera = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            camera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(camera);
        } catch (Exception e) {
            Toast.makeText(this, "카메라를 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    List<LauncherApp> loadLauncherApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(intent, 0);
        ArrayList<LauncherApp> result = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName)) continue;
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            result.add(new LauncherApp(label, packageName));
        }
        Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }

    private void requestHomeRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        RoleManager roleManager = getSystemService(RoleManager.class);
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                || roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return;
        try {
            startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), HOME_ROLE_REQUEST);
        } catch (Exception ignored) {
            // The user can still select the launcher through system settings.
        }
    }

    boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String component = new ComponentName(this, PlaybackNotificationListener.class).flattenToString();
        return enabled.contains(component) || enabled.contains(getPackageName());
    }

    void openNotificationAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showNotificationAccessExplanation() {
        if (isFinishing() || isNotificationListenerEnabled()) return;
        new AlertDialog.Builder(this)
                .setTitle("음악과 알림 점 연결")
                .setMessage("곡 제목·재생 상태·앱별 알림 표시를 홈 화면에 연결하려면 OTER.Y Home의 알림 접근을 허용해 주세요. 내용은 휴대폰 밖으로 전송되지 않습니다.")
                .setPositiveButton("설정 열기", (dialog, which) -> openNotificationAccessSettings())
                .setNegativeButton("나중에", null)
                .show();
    }

    static final class LauncherApp {
        final String label;
        final String packageName;

        LauncherApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
