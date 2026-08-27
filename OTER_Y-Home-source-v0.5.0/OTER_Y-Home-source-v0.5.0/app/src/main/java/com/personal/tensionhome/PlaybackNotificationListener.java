package com.personal.tensionhome;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaybackNotificationListener extends NotificationListenerService {
    static final String ACTION_MEDIA_STATE = "com.personal.tensionhome.MEDIA_STATE";
    static final String ACTION_SNAPSHOT = "com.personal.tensionhome.REQUEST_SNAPSHOT";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_ARTIST = "artist";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_PLAYING = "playing";
    static final String EXTRA_NOTIFICATION_PACKAGES = "notification_packages";

    private MediaSessionManager mediaSessionManager;
    private MediaController observedController;
    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishSnapshot();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishSnapshot();
        }

        @Override
        public void onSessionDestroyed() {
            attachPreferredController(null);
            publishSnapshot();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = sessions -> {
        attachPreferredController(sessions);
        publishSnapshot();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSessionManager = getSystemService(MediaSessionManager.class);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                        sessionListener, new ComponentName(this, PlaybackNotificationListener.class));
                attachPreferredController(mediaSessionManager.getActiveSessions(
                        new ComponentName(this, PlaybackNotificationListener.class)));
            }
        } catch (SecurityException ignored) {
        }
        publishSnapshot();
    }

    @Override
    public void onListenerDisconnected() {
        try {
            attachPreferredController(null);
            if (mediaSessionManager != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            }
        } catch (Exception ignored) {
        }
        super.onListenerDisconnected();
    }

    private void attachPreferredController(List<MediaController> controllers) {
        MediaController next = null;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (next == null) next = controller;
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    next = controller;
                    break;
                }
            }
        }
        if (observedController == next) return;
        if (observedController != null) {
            try {
                observedController.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {
            }
        }
        observedController = next;
        if (observedController != null) {
            try {
                observedController.registerCallback(controllerCallback);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        publishSnapshot();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        publishSnapshot();
    }

    private void publishSnapshot() {
        MediaSnapshot snapshot = readMediaSnapshot(this);
        Intent update = new Intent(ACTION_MEDIA_STATE).setPackage(getPackageName());
        update.putExtra(EXTRA_TITLE, snapshot.title);
        update.putExtra(EXTRA_ARTIST, snapshot.artist);
        update.putExtra(EXTRA_SOURCE, snapshot.source);
        update.putExtra(EXTRA_PLAYING, snapshot.playing);
        update.putExtra(EXTRA_NOTIFICATION_PACKAGES, notificationPackages());
        sendBroadcast(update);
    }

    private String[] notificationPackages() {
        Set<String> packages = new HashSet<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification item : active) {
                    Notification n = item.getNotification();
                    if (n != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) {
                        packages.add(item.getPackageName());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return packages.toArray(new String[0]);
    }

    static void requestSnapshot(Context context) {
        try {
            requestRebind(new ComponentName(context, PlaybackNotificationListener.class));
        } catch (Exception ignored) {
        }
        MediaSnapshot snapshot = readMediaSnapshot(context);
        Intent update = new Intent(ACTION_MEDIA_STATE).setPackage(context.getPackageName());
        update.putExtra(EXTRA_TITLE, snapshot.title);
        update.putExtra(EXTRA_ARTIST, snapshot.artist);
        update.putExtra(EXTRA_SOURCE, snapshot.source);
        update.putExtra(EXTRA_PLAYING, snapshot.playing);
        context.sendBroadcast(update);
    }

    static boolean togglePlayback(Context context) {
        MediaController controller = preferredController(context);
        if (controller == null) return false;
        PlaybackState state = controller.getPlaybackState();
        try {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MediaSnapshot readMediaSnapshot(Context context) {
        MediaController controller = preferredController(context);
        if (controller == null) return MediaSnapshot.EMPTY;

        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        String title = "";
        String artist = "";
        if (metadata != null) {
            title = firstNonEmpty(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            artist = firstNonEmpty(
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        }
        String source;
        try {
            source = String.valueOf(context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager().getApplicationInfo(controller.getPackageName(), 0)));
        } catch (Exception e) {
            source = controller.getPackageName();
        }
        boolean playing = playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
        return new MediaSnapshot(title, artist, source, playing);
    }

    private static MediaController preferredController(Context context) {
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            if (manager == null) return null;
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(context, PlaybackNotificationListener.class));
            MediaController fallback = null;
            for (MediaController controller : controllers) {
                if (fallback == null) fallback = controller;
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return controller;
            }
            return fallback;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    static final class MediaSnapshot {
        static final MediaSnapshot EMPTY = new MediaSnapshot("", "", "", false);
        final String title;
        final String artist;
        final String source;
        final boolean playing;

        MediaSnapshot(String title, String artist, String source, boolean playing) {
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.playing = playing;
        }
    }
}
