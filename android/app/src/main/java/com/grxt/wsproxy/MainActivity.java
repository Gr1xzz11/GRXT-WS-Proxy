package com.grxt.wsproxy;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 17, 31);
    private static final int CARD = Color.rgb(18, 29, 48);
    private static final int CARD_SOFT = Color.rgb(23, 36, 58);
    private static final int TEXT = Color.rgb(244, 248, 255);
    private static final int MUTED = Color.rgb(151, 166, 190);
    private static final int BLUE = Color.rgb(42, 120, 255);
    private static final int BLUE_DARK = Color.rgb(28, 91, 211);
    private static final int GREEN = Color.rgb(67, 211, 139);
    private static final int RED = Color.rgb(255, 92, 110);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView route;
    private TextView routeHint;
    private TextView error;
    private TextView statusDot;
    private Button toggle;
    private Button telegram;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        handler.post(refreshTask);
    }

    private ScrollView buildUi() {
        int side = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(side, dp(22), side, dp(30));
        root.setBackgroundColor(BG);

        TextView brand = text("GRXT", 13, true, BLUE);
        brand.setLetterSpacing(0.14f);
        root.addView(brand);

        TextView title = text("WS Proxy", 32, true, TEXT);
        add(root, title, 0, dp(2), 0, 0);

        TextView sub = text("Telegram через локальный MTProto → WebSocket/TLS", 14, false, MUTED);
        add(root, sub, 0, dp(6), 0, dp(22));

        LinearLayout statusCard = card();
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 18, true, MUTED);
        statusRow.addView(statusDot, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
        status = text("Прокси выключен", 20, true, TEXT);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusCard.addView(statusRow);

        route = text("Маршрут: выключен", 15, true, TEXT);
        add(statusCard, route, 0, dp(15), 0, 0);
        routeHint = text("После запуска приложение само проверит доступный маршрут.", 13, false, MUTED);
        add(statusCard, routeHint, 0, dp(5), 0, 0);

        TextView endpoint = text("127.0.0.1:1443", 13, false, MUTED);
        add(statusCard, endpoint, 0, dp(13), 0, 0);
        root.addView(statusCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toggle = new Button(this);
        toggle.setText("ВКЛЮЧИТЬ ПРОКСИ");
        toggle.setTextSize(17);
        toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggle.setTextColor(Color.WHITE);
        toggle.setAllCaps(false);
        toggle.setGravity(Gravity.CENTER);
        toggle.setElevation(dp(6));
        toggle.setBackground(rounded(BLUE, 18));
        toggle.setOnClickListener(v -> {
            toggle.setEnabled(false);
            if (ProxyService.running) ProxyService.stop(this); else ProxyService.start(this);
            handler.postDelayed(() -> {
                refresh();
                toggle.setEnabled(true);
            }, 450);
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        startParams.setMargins(0, dp(18), 0, 0);
        root.addView(toggle, startParams);

        telegram = new Button(this);
        telegram.setText("Подключить Telegram");
        telegram.setTextSize(16);
        telegram.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        telegram.setTextColor(TEXT);
        telegram.setAllCaps(false);
        telegram.setBackground(rounded(CARD_SOFT, 16));
        telegram.setOnClickListener(v -> connectTelegram());
        LinearLayout.LayoutParams tgParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        tgParams.setMargins(0, dp(12), 0, 0);
        root.addView(telegram, tgParams);

        TextView section = text("Как это работает", 18, true, TEXT);
        add(root, section, 0, dp(28), 0, dp(10));

        LinearLayout infoCard = card();
        infoCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        infoCard.addView(step("1", "Включи прокси", "GRXT проверит доступный маршрут автоматически."));
        infoCard.addView(step("2", "Подключи Telegram", "Нажми кнопку выше и подтверди MTProto-прокси."));
        infoCard.addView(step("3", "Можно закрыть приложение", "Сервис продолжит работу в фоне и останется в уведомлениях."));
        root.addView(infoCard);

        error = text("", 13, true, RED);
        add(root, error, 0, dp(16), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout step(String n, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView badge = text(n, 14, true, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(BLUE_DARK, 12));
        row.addView(badge, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, true, TEXT);
        TextView d = text(desc, 13, false, MUTED);
        d.setLineSpacing(0, 1.1f);
        copy.addView(t);
        add(copy, d, 0, dp(3), 0, 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.setMargins(dp(12), 0, 0, 0);
        row.addView(copy, cp);
        return row;
    }

    private void connectTelegram() {
        if (!ProxyService.running) ProxyService.start(this);
        handler.postDelayed(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Settings.telegramLink(this)));
            try { startActivity(i); }
            catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Telegram не найден", Toast.LENGTH_LONG).show();
            }
        }, 450);
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    private void refresh() {
        boolean on = ProxyService.running;
        status.setText(on ? "Прокси включён" : "Прокси выключен");
        statusDot.setTextColor(on ? GREEN : MUTED);

        String activeRoute = ProxyService.route == null ? "" : ProxyService.route.trim();
        if (!on) {
            route.setText("Маршрут: выключен");
            routeHint.setText("Нажми большую синюю кнопку, чтобы запустить прокси.");
        } else if (activeRoute.isEmpty() || "off".equalsIgnoreCase(activeRoute)) {
            route.setText("Маршрут: Auto");
            routeHint.setText("Проверяем доступность WebSocket/TCP…");
        } else {
            route.setText("Маршрут: " + activeRoute);
            if (activeRoute.contains("проверка")) routeHint.setText("Проверяем доступность WebSocket/TCP…");
            else if (activeRoute.contains("недоступен")) routeHint.setText("Маршрут не найден. Проверь интернет или открой ошибку ниже.");
            else routeHint.setText("Маршрут готов. Теперь можно подключать Telegram.");
        }

        toggle.setText(on ? "ВЫКЛЮЧИТЬ ПРОКСИ" : "ВКЛЮЧИТЬ ПРОКСИ");
        toggle.setBackground(rounded(on ? Color.rgb(43, 52, 72) : BLUE, 18));
        telegram.setEnabled(on);
        telegram.setAlpha(on ? 1f : 0.55f);
        error.setText(ProxyService.error.isEmpty() ? "" : "Ошибка: " + ProxyService.error);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(CARD, 20));
        box.setElevation(dp(2));
        return box;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private void add(LinearLayout root, View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        root.addView(v, p);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
