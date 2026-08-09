package com.grxt.wsproxy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    private static final int BG = Color.rgb(7, 13, 24);
    private static final int CARD = Color.rgb(15, 27, 45);
    private static final int CARD_2 = Color.rgb(20, 35, 57);
    private static final int TEXT = Color.rgb(246, 249, 255);
    private static final int MUTED = Color.rgb(145, 161, 186);
    private static final int BLUE = Color.rgb(25, 112, 255);
    private static final int BLUE_2 = Color.rgb(17, 82, 203);
    private static final int GREEN = Color.rgb(53, 218, 139);
    private static final int RED = Color.rgb(255, 91, 105);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView route;
    private TextView details;
    private TextView error;
    private Button toggle;
    private Button telegram;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        handler.post(refreshTask);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        root.setBackgroundColor(BG);

        TextView brand = text("GRXT WS Proxy", 30, true, TEXT);
        root.addView(brand);
        TextView sub = text("Локальный Telegram proxy · Android", 14, false, MUTED);
        add(root, sub, 0, dp(5), 0, dp(20));

        LinearLayout statusCard = card();
        statusCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        status = text("Прокси выключен", 21, true, TEXT);
        statusCard.addView(status);
        route = text("Маршрут: выключен", 15, true, TEXT);
        add(statusCard, route, 0, dp(13), 0, 0);
        details = text("Локальный адрес: 127.0.0.1:1080", 13, false, MUTED);
        add(statusCard, details, 0, dp(7), 0, 0);
        TextView core = text("Режим: SOCKS5 → WebSocket/TLS → Telegram DC", 13, false, MUTED);
        add(statusCard, core, 0, dp(5), 0, 0);
        root.addView(statusCard, matchWrap());

        toggle = mainButton("ВКЛЮЧИТЬ ПРОКСИ", BLUE);
        toggle.setOnClickListener(v -> {
            toggle.setEnabled(false);
            if (ProxyService.running) ProxyService.stop(this); else ProxyService.start(this);
            handler.postDelayed(() -> { refresh(); toggle.setEnabled(true); }, 500);
        });
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        big.setMargins(0, dp(18), 0, 0);
        root.addView(toggle, big);

        telegram = mainButton("ПОДКЛЮЧИТЬ TELEGRAM", CARD_2);
        telegram.setTextColor(Color.rgb(95, 163, 255));
        telegram.setOnClickListener(v -> connectTelegram());
        LinearLayout.LayoutParams tg = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        tg.setMargins(0, dp(11), 0, 0);
        root.addView(telegram, tg);

        TextView quick = text("Быстрые действия", 18, true, TEXT);
        add(root, quick, 0, dp(25), 0, dp(9));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        addActionRow(grid,
                action("РЕСТАРТ", v -> { ProxyService.restart(this); toast("Прокси перезапускается"); }),
                action("ПРОВЕРИТЬ МАРШРУТ", v -> { ProxyService.restart(this); toast("Маршруты проверяются заново"); }));
        addActionRow(grid,
                action("СКОПИРОВАТЬ АДРЕС", v -> copyAddress()),
                action("СТАТУС", v -> showStatus()));
        addActionRow(grid,
                action("ОТКРЫТЬ TELEGRAM", v -> connectTelegram()),
                action("ОСТАНОВИТЬ", v -> ProxyService.stop(this)));
        root.addView(grid);

        LinearLayout check = card();
        check.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView ct = text("Текущее состояние", 17, true, TEXT);
        check.addView(ct);
        TextView c1 = text("WebSocket: автоматический выбор", 13, false, MUTED);
        add(check, c1, 0, dp(10), 0, 0);
        TextView c2 = text("Cloudflare: резервный маршрут", 13, false, MUTED);
        add(check, c2, 0, dp(5), 0, 0);
        TextView c3 = text("TCP: последний fallback", 13, false, MUTED);
        add(check, c3, 0, dp(5), 0, 0);
        add(root, check, 0, dp(18), 0, 0);

        error = text("", 13, true, RED);
        add(root, error, 0, dp(14), 0, 0);

        TextView note = text("GRXT сначала пробует WebSocket, затем Cloudflare, затем обычный TCP. Telegram подключается к локальному SOCKS5 без отдельного MTProxy-secret.", 12, false, MUTED);
        note.setLineSpacing(0, 1.15f);
        add(root, note, 0, dp(15), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        return scroll;
    }

    private Button action(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(CARD_2, 15));
        b.setOnClickListener(listener);
        return b;
    }

    private void addActionRow(LinearLayout grid, Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        lp.setMargins(0, 0, dp(5), dp(8));
        row.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        rp.setMargins(dp(5), 0, 0, dp(8));
        row.addView(right, rp);
        grid.addView(row, matchWrap());
    }

    private Button mainButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setElevation(dp(5));
        b.setBackground(rounded(color, 18));
        return b;
    }

    private void connectTelegram() {
        if (!ProxyService.running) {
            ProxyService.start(this);
            telegram.setEnabled(false);
            telegram.setText("ЗАПУСК ПРОКСИ…");
            waitAndOpenTelegram(0);
        } else {
            openTelegram();
        }
    }

    private void waitAndOpenTelegram(int n) {
        if (ProxyService.running) {
            telegram.setEnabled(true);
            telegram.setText("ПОДКЛЮЧИТЬ TELEGRAM");
            openTelegram();
            return;
        }
        if (n >= 40) {
            telegram.setEnabled(true);
            telegram.setText("ПОДКЛЮЧИТЬ TELEGRAM");
            toast("Прокси не запустился. Проверь ошибку на главном экране.");
            return;
        }
        handler.postDelayed(() -> waitAndOpenTelegram(n + 1), 125);
    }

    private void openTelegram() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Settings.telegramLink(this)));
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { toast("Telegram не найден"); }
    }

    private void copyAddress() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("GRXT proxy", Settings.HOST + ":" + Settings.PORT));
        toast("Скопировано: " + Settings.HOST + ":" + Settings.PORT);
    }

    private void showStatus() {
        String text = "Сервис: " + (ProxyService.running ? "работает" : "выключен") +
                "\nМаршрут: " + ProxyService.route +
                (ProxyService.error.isEmpty() ? "" : "\nОшибка: " + ProxyService.error);
        new AlertDialog.Builder(this)
                .setTitle("GRXT WS Proxy")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 650);
        }
    };

    private void refresh() {
        boolean on = ProxyService.running;
        status.setText(on ? "Прокси активен" : "Прокси выключен");
        status.setTextColor(on ? GREEN : TEXT);
        String r = ProxyService.route == null ? "" : ProxyService.route.trim();
        route.setText("Маршрут: " + (r.isEmpty() || "off".equalsIgnoreCase(r) ? (on ? "Auto" : "выключен") : r));
        details.setText("Локальный адрес: " + Settings.HOST + ":" + Settings.PORT);
        toggle.setText(on ? "ВЫКЛЮЧИТЬ ПРОКСИ" : "ВКЛЮЧИТЬ ПРОКСИ");
        toggle.setBackground(rounded(on ? BLUE_2 : BLUE, 18));
        telegram.setAlpha(on ? 1f : 0.75f);
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
        d.setStroke(dp(1), Color.rgb(35, 58, 88));
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
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(l, t, r, b);
        root.addView(v, p);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
