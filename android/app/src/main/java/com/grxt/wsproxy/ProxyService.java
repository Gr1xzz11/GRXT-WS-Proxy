package com.grxt.wsproxy;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class ProxyService extends Service {
    public static final String ACTION_START = "com.grxt.wsproxy.START";
    public static final String ACTION_STOP = "com.grxt.wsproxy.STOP";
    private static final String CHANNEL = "grxt_proxy";
    private static final int NOTIFICATION_ID = 1443;

    public static volatile boolean running = false;
    public static volatile String route = "off";
    public static volatile String error = "";

    private MtProtoProxyEngine engine;

    public static void start(Context context) {
        Intent i = new Intent(context, ProxyService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ProxyService.class).setAction(ACTION_STOP));
    }

    public static void restart(Context context) {
        stop(context);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> start(context), 500);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Запуск MTProto-прокси…"));
        if (engine == null || !engine.isRunning()) {
            try {
                error = "";
                route = "Запуск…";
                engine = new MtProtoProxyEngine(Settings.secret(this), (state, activeRoute) -> {
                    running = "running".equals(state);
                    route = activeRoute == null ? "" : activeRoute;
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, notification(
                                running ? "MTProto работает · " + route : "Прокси выключен"));
                    }
                });
                engine.start();
                running = true;
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                running = false;
                route = "error";
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID, notification("Ошибка: " + error));
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ProxyService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("GRXT WS Proxy")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(running)
                .addAction(new Notification.Action.Builder(null, "Отключить", stopPi).build())
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "GRXT WS Proxy",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Состояние локального Telegram MTProto-прокси");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void shutdown() {
        if (engine != null) engine.close();
        engine = null;
        running = false;
        route = "off";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
