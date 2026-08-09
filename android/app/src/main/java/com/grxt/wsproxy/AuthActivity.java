package com.grxt.wsproxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AuthActivity extends Activity {
    private static final int BG = Color.rgb(7, 13, 24);
    private static final int CARD = Color.rgb(15, 27, 45);
    private static final int CARD_2 = Color.rgb(20, 35, 57);
    private static final int TEXT = Color.rgb(246, 249, 255);
    private static final int MUTED = Color.rgb(145, 161, 186);
    private static final int BLUE = Color.rgb(25, 112, 255);
    private static final int GREEN = Color.rgb(53, 218, 139);
    private static final int RED = Color.rgb(255, 91, 105);

    private GrxtAuth auth;
    private TextView authState;
    private TextView grxtId;
    private TextView emailState;
    private TextView deviceState;
    private TextView result;
    private EditText email;
    private EditText password;
    private Button signIn;
    private Button signUp;
    private Button resend;
    private Button sync;
    private Button signOut;
    private Button back;
    private boolean required;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        auth = new GrxtAuth(this);
        required = getIntent().getBooleanExtra("required", false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        refresh();

        if (handleAuthIntent(getIntent())) return;

        if (auth.isSignedIn()) {
            setBusy(true);
            result.setText("Проверка сессии GRXT Auth…");
            auth.restore((ok, message) -> {
                setBusy(false);
                if (!ok || !auth.isSignedIn()) {
                    ProxyService.stop(this);
                    auth.signOut((ignored, ignoredMessage) -> {
                        result.setText("Сессия недействительна. Войди снова.");
                        result.setTextColor(RED);
                        refresh();
                    });
                    return;
                }
                result.setText(message);
                result.setTextColor(GREEN);
                refresh();
                if (required) openProxy();
            });
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
    }

    private boolean handleAuthIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"grxt".equalsIgnoreCase(data.getScheme()) || !"auth".equalsIgnoreCase(data.getHost())) {
            return false;
        }

        setBusy(true);
        result.setText("Подтверждаем email через Supabase…");
        result.setTextColor(MUTED);
        auth.consumeAuthRedirect(data, (ok, message) -> {
            setBusy(false);
            result.setText(message);
            result.setTextColor(ok ? GREEN : RED);
            refresh();
            if (ok && auth.isSignedIn()) openProxy();
        });
        return true;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        root.setBackgroundColor(BG);

        root.addView(text("GRXT Auth", 30, true, TEXT));
        add(root, text("Авторизация обязательна для GRXT WS Proxy", 14, false, MUTED), 0, dp(5), 0, dp(20));

        LinearLayout account = card();
        account.setPadding(dp(18), dp(17), dp(18), dp(17));
        authState = text("ТРЕБУЕТСЯ ВХОД", 21, true, RED);
        account.addView(authState);
        grxtId = text("GRXT ID: —", 16, true, TEXT);
        add(account, grxtId, 0, dp(12), 0, 0);
        emailState = text("Email: —", 13, false, MUTED);
        add(account, emailState, 0, dp(7), 0, 0);
        deviceState = text("Устройство: —", 13, false, MUTED);
        add(account, deviceState, 0, dp(5), 0, 0);
        root.addView(account, matchWrap());

        add(root, text("Вход или регистрация", 18, true, TEXT), 0, dp(24), 0, dp(10));

        email = field("Email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        password = field("Пароль");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        passLp.setMargins(0, dp(10), 0, 0);
        root.addView(password, passLp);

        signIn = mainButton("ВОЙТИ", BLUE);
        signIn.setOnClickListener(v -> runAuth(false));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        inLp.setMargins(0, dp(14), 0, 0);
        root.addView(signIn, inLp);

        signUp = mainButton("СОЗДАТЬ АККАУНТ", CARD_2);
        signUp.setOnClickListener(v -> runAuth(true));
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        upLp.setMargins(0, dp(10), 0, 0);
        root.addView(signUp, upLp);

        resend = action("ПОВТОРИТЬ ПИСЬМО", v -> resendEmail());
        LinearLayout.LayoutParams resendLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        resendLp.setMargins(0, dp(9), 0, 0);
        root.addView(resend, resendLp);

        add(root, text("Аккаунт", 18, true, TEXT), 0, dp(24), 0, dp(9));

        sync = action("СИНХРОНИЗИРОВАТЬ", v -> {
            setBusy(true);
            auth.syncNow((ok, message) -> {
                setBusy(false);
                result.setText(message);
                result.setTextColor(ok ? GREEN : RED);
                refresh();
            });
        });
        root.addView(sync, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        signOut = action("ВЫЙТИ ИЗ GRXT AUTH", v -> {
            setBusy(true);
            ProxyService.stop(this);
            auth.signOut((ok, message) -> {
                setBusy(false);
                result.setText("Вы вышли. Для использования прокси требуется вход.");
                result.setTextColor(TEXT);
                refresh();
            });
        });
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        outLp.setMargins(0, dp(9), 0, 0);
        root.addView(signOut, outLp);

        back = action("НАЗАД К ПРОКСИ", v -> {
            if (auth.isSignedIn()) openProxy();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        backLp.setMargins(0, dp(9), 0, 0);
        root.addView(back, backLp);

        result = text("Регистрация теперь проверяется по ответу Supabase Auth. Приложение не будет считать аккаунт созданным по одному HTTP 200.", 13, false, MUTED);
        result.setLineSpacing(0, 1.15f);
        add(root, result, 0, dp(18), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        return scroll;
    }

    private void runAuth(boolean create) {
        String mail = email.getText().toString().trim();
        String pass = password.getText().toString();
        setBusy(true);
        result.setText(create ? "Проверяем регистрацию в Supabase Auth…" : "Вход в GRXT Auth…");
        result.setTextColor(MUTED);
        GrxtAuth.Callback cb = (ok, message) -> {
            setBusy(false);
            result.setText(message);
            result.setTextColor(ok ? GREEN : RED);
            if (ok && auth.isSignedIn()) password.setText("");
            refresh();
            if (ok && auth.isSignedIn()) openProxy();
        };
        if (create) auth.signUp(mail, pass, cb); else auth.signIn(mail, pass, cb);
    }

    private void resendEmail() {
        String mail = email.getText().toString().trim();
        setBusy(true);
        result.setText("Запрашиваем повторное письмо у Supabase…");
        result.setTextColor(MUTED);
        auth.resendConfirmation(mail, (ok, message) -> {
            setBusy(false);
            result.setText(message);
            result.setTextColor(ok ? GREEN : RED);
            refresh();
        });
    }

    private void openProxy() {
        if (!auth.isSignedIn()) return;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void setBusy(boolean busy) {
        signIn.setEnabled(!busy);
        signUp.setEnabled(!busy);
        resend.setEnabled(!busy);
        sync.setEnabled(!busy && auth.isSignedIn());
        signOut.setEnabled(!busy && auth.isSignedIn());
        back.setEnabled(!busy && auth.isSignedIn());
    }

    private void refresh() {
        boolean in = auth.isSignedIn();
        authState.setText(in ? "GRXT AUTH ПОДКЛЮЧЁН" : "ТРЕБУЕТСЯ ВХОД");
        authState.setTextColor(in ? GREEN : RED);
        grxtId.setText("GRXT ID: " + (in ? auth.grxtId() : "—"));
        emailState.setText("Email: " + (in ? auth.email() : "—"));
        deviceState.setText("Устройство: " + auth.deviceId());
        sync.setVisibility(in ? View.VISIBLE : View.GONE);
        signOut.setVisibility(in ? View.VISIBLE : View.GONE);
        back.setVisibility(in ? View.VISIBLE : View.GONE);
        signIn.setVisibility(in ? View.GONE : View.VISIBLE);
        signUp.setVisibility(in ? View.GONE : View.VISIBLE);
        resend.setVisibility(in ? View.GONE : View.VISIBLE);
        email.setVisibility(in ? View.GONE : View.VISIBLE);
        password.setVisibility(in ? View.GONE : View.VISIBLE);
        sync.setEnabled(in);
        signOut.setEnabled(in);
        back.setEnabled(in);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(16), 0, dp(16), 0);
        e.setBackground(rounded(CARD, 15));
        return e;
    }

    private Button mainButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setElevation(dp(4));
        b.setBackground(rounded(color, 17));
        return b;
    }

    private Button action(String label, View.OnClickListener listener) {
        Button b = mainButton(label, CARD_2);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        return b;
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
}
