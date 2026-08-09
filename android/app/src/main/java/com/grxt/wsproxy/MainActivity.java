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
    private TextView authState;
    private TextView error;
    private Button toggle;
    private Button telegram;
    private GrxtAuth auth;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        auth = new GrxtAuth(this);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        handler.post(refreshTask);
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        root.setBackgroundColor(BG);

        root.addView(text("GRXT WS Proxy", 30, true, TEXT));
        add(root, text("Telegram MTProto · GRXT Cloud", 14, false, MUTED), 0, dp(5), 0, dp(20));

        LinearLayout statusCard = card();
        statusCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        status = text("Прокси выключен", 21, true, TEXT);
        statusCard.addView(status);
        route = text("Маршрут: выключен", 15, true, TEXT);
        add(statusCard, route, 0, dp(13), 0, 0);
        details = text("Локальный адрес: 127.0.0.1:1443", 13, false, MUTED);
        add(statusCard, details, 0, dp(7), 0, 0);
        add(statusCard, text("Режим: MTProto → WebSocket/TLS → Telegram DC", 13, false, MUTED), 0, dp(5), 0, 0);
        root.addView(statusCard, matchWrap());

        LinearLayout account = card();
        account.setPadding(dp(18), dp(15), dp(18), dp(15));
        TextView accountTitle = text("GRXT Auth", 17, true, TEXT);
        account.addView(accountTitle);
        authState = text("Гостевой режим · GRXT ID: —", 13, false, MUTED);
        add(account, authState, 0, dp(8), 0, 0);
        Button accountButton = action("ОТКРЫТЬ GRXT AUTH", v -> openAuth());
        LinearLayout.LayoutParams accountLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        accountLp.setMargins(0, dp(12), 0, 0);
        account.addView(accountButton, accountLp);
        add(root, account, 0, dp(12), 0, 0);

        toggle = mainButton("ВКЛЮЧИТЬ ПРОКСИ", BLUE);
        toggle.setOnClickListener(v -> {
            toggle.setEnabled(false);
            if (ProxyService.running) ProxyService.stop(this); else ProxyService.start(this);
            handler.postDelayed(() -> { refresh(); toggle.setEnabled(true); }, 550);
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
                action("РЕСТАРТ", v -> { ProxyService.restart(this); toast("MTProto перезапускается"); }),
                action("ПРОВЕРИТЬ МАРШРУТ", v -> { ProxyService.restart(this); toast("Маршруты проверяются заново"); }));
        addActionRow(grid,
                action("СКОПИРОВАТЬ АДРЕС", v -> copyAddress()),
                action("СКОПИРОВАТЬ SECRET", v -> copySecret()));
        addActionRow(grid,
                action("НОВЫЙ SECRET", v -> regenerateSecret()),
                action("СТАТУС", v -> showStatus()));
        addActionRow(grid,
                action("GRXT AUTH", v -> openAuth()),
                action("ОСТАНОВИТЬ", v -> ProxyService.stop(this)));
        root.addView(grid);

        LinearLayout check = card();
        check.setPadding(dp(18), dp(16), dp(18), dp(16));
        check.addView(text("Текущее состояние", 17, true, TEXT));
        add(check, text("MTProto listener: 127.0.0.1:1443", 13, false, MUTED), 0, dp(10), 0, 0);
        add(check, text("WebSocket: прямой Telegram маршрут", 13, false, MUTED), 0, dp(5), 0, 0);
        add(check, text("Cloudflare: резервный WebSocket маршрут", 13, false, MUTED), 0, dp(5), 0, 0);
        add(check, text("GRXT Cloud: Auth + устройство + статистика", 13, false, MUTED), 0, dp(5), 0, 0);
        add(root, check, 0, dp(18), 0, 0);

        error = text("", 13, true, RED);
        add(root, error, 0, dp(14), 0, 0);

        TextView note = text("Аккаунт не обязателен. В гостевом режиме прокси работает локально. После входа GRXT Auth создаёт постоянный GRXT ID и привязывает устройство к Supabase.", 12, false, MUTED);
        note.setLineSpacing(0, 1.15f);
        add(root, note, 0, dp(15), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        return scroll;
    }

    private void openAuth() {
        startActivity(new Intent(this, AuthActivity.class));
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
            telegram.setText("ЗАПУСК MTProto…");
            waitAndOpenTelegram(0);
        } else {
            openTelegram();
        }
    }

    private void waitAndOpenTelegram(int n) {
        if (ProxyService.running) {
            handler.postDelayed(() -> {
                telegram.setEnabled(true);
                telegram.setText("ПОДКЛЮЧИТЬ TELEGRAM");
                openTelegram();
            }, 250);
            return;
        }
        if (n >= 50) {
            telegram.setEnabled(true);
            telegram.setText("ПОДКЛЮЧИТЬ TELEGRAM");
            toast("MTProto не запустился. Проверь ошибку на главном экране.");
            return;
        }
        handler.postDelayed(() -> waitAndOpenTelegram(n + 1), 120);
    }

    private void openTelegram() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Settings.telegramLink(this)));
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { toast("Telegram не найден"); }
    }

    private void copyAddress() {
        copy("GRXT MTProto address", Settings.HOST + ":" + Settings.PORT);
        toast("Скопировано: " + Settings.HOST + ":" + Settings.PORT);
    }

    private void copySecret() {
        copy("GRXT MTProto secret", Settings.secret(this));
        toast("MTProto secret скопирован");
    }

    private void regenerateSecret() {
        boolean wasRunning = ProxyService.running;
        if (wasRunning) ProxyService.stop(this);
        Settings.regenerateSecret(this);
        handler.postDelayed(() -> {
            if (wasRunning) ProxyService.start(this);
            toast("Новый secret создан. Переподключи прокси в Telegram.");
        }, 600);
    }

    private void copy(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value));
    }

    private void showStatus() {
        String cloud = auth.isSignedIn() ? auth.grxtId() : "гость";
        String value = "Сервис: " + (ProxyService.running ? "работает" : "выключен") +
                "\nАдрес: " + Settings.HOST + ":" + Settings.PORT +
                "\nРежим: MTProto" +
                "\nМаршрут: " + ProxyService.route +
                "\nGRXT Auth: " + cloud +
                (ProxyService.error.isEmpty() ? "" : "\nОшибка: " + ProxyService.error);
        new AlertDialog.Builder(this)
                .setTitle("GRXT WS Proxy")
                .setMessage(value)
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
        status.setText(on ? "MTProto прокси активен" : "Прокси выключен");
        status.setTextColor(on ? GREEN : TEXT);
        String r = ProxyService.route == null ? "" : ProxyService.route.trim();
        route.setText("Маршрут: " + (r.isEmpty() || "off".equalsIgnoreCase(r) ? (on ? "Auto" : "выключен") : r));
        details.setText("Локальный адрес: " + Settings.HOST + ":" + Settings.PORT);
        if (auth.isSignedIn()) {
            authState.setText("Подключён · " + auth.grxtId() + " · " + auth.email());
            authState.setTextColor(GREEN);
        } else {
            authState.setText("Гостевой режим · GRXT ID: —");
            authState.setTextColor(MUTED);
        }
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
