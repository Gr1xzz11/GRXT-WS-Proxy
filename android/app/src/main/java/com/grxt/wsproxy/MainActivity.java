package com.grxt.wsproxy;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView route;
    private TextView error;
    private Button toggle;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        setContentView(buildUi());
        handler.post(refreshTask);
    }

    private ScrollView buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = text("GRXT WS Proxy", 28, true);
        root.addView(title);
        TextView sub = text("Telegram через локальный MTProto → WebSocket/TLS", 15, false);
        sub.setTextColor(Color.DKGRAY);
        add(root, sub, 0, 0, 0, dp(24));

        status = text("Прокси выключен", 21, true);
        root.addView(status);
        route = text("Маршрут: off", 14, false);
        add(root, route, 0, dp(7), 0, 0);
        TextView endpoint = text("127.0.0.1:1443", 14, false);
        add(root, endpoint, 0, dp(4), 0, dp(20));

        toggle = new Button(this);
        toggle.setText("Включить");
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> {
            if (ProxyService.running) ProxyService.stop(this); else ProxyService.start(this);
            handler.postDelayed(this::refresh, 300);
        });
        root.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button telegram = new Button(this);
        telegram.setText("Подключить Telegram");
        telegram.setAllCaps(false);
        telegram.setOnClickListener(v -> connectTelegram());
        add(root, telegram, 0, dp(10), 0, 0);

        TextView infoTitle = text("Как использовать", 18, true);
        add(root, infoTitle, 0, dp(28), 0, dp(8));
        TextView info = text("1. Нажми «Включить».\n2. Нажми «Подключить Telegram».\n3. Подтверди MTProto-прокси в Telegram.\n\nПриложение можно закрыть: прокси продолжит работать через foreground service. Остановить его можно кнопкой здесь или из уведомления.", 15, false);
        info.setLineSpacing(0, 1.15f);
        root.addView(info);

        error = text("", 13, false);
        error.setTextColor(Color.rgb(180, 30, 30));
        add(root, error, 0, dp(18), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void connectTelegram() {
        if (!ProxyService.running) ProxyService.start(this);
        handler.postDelayed(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Settings.telegramLink(this)));
            try { startActivity(i); }
            catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Telegram не найден", Toast.LENGTH_LONG).show();
            }
        }, 350);
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        boolean on = ProxyService.running;
        status.setText(on ? "Прокси работает" : "Прокси выключен");
        toggle.setText(on ? "Выключить" : "Включить");
        route.setText("Маршрут: " + ProxyService.route);
        error.setText(ProxyService.error.isEmpty() ? "" : "Ошибка: " + ProxyService.error);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(20,20,20));
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return v;
    }

    private void add(LinearLayout root, android.view.View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l,t,r,b); root.addView(v,p);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
