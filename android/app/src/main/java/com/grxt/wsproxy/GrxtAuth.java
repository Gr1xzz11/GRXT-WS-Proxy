package com.grxt.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GrxtAuth {
    interface Callback {
        void done(boolean ok, String message);
    }

    private static final String PREFS = "grxt_auth";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_USER = "user_id";
    private static final String K_EMAIL = "email";
    private static final String K_EXPIRES = "expires_at";

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    GrxtAuth(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isSignedIn() {
        return !accessToken().isEmpty() && !userId().isEmpty();
    }

    String email() { return prefs.getString(K_EMAIL, "") == null ? "" : prefs.getString(K_EMAIL, ""); }
    String userId() { return prefs.getString(K_USER, "") == null ? "" : prefs.getString(K_USER, ""); }
    String accessToken() { return prefs.getString(K_ACCESS, "") == null ? "" : prefs.getString(K_ACCESS, ""); }
    String refreshToken() { return prefs.getString(K_REFRESH, "") == null ? "" : prefs.getString(K_REFRESH, ""); }

    String grxtId() {
        String uid = userId();
        return uid.isEmpty() ? "—" : formatGrxtId(uid);
    }

    String deviceId() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown-device";
        String h = sha256(androidId + ":" + context.getPackageName()).substring(0, 12).toUpperCase(Locale.ROOT);
        return "DEV-" + h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12);
    }

    void signUp(String email, String password, Callback cb) {
        if (!valid(email, password, cb)) return;
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("email", email.trim()).put("password", password);
                Response r = request("POST", "/auth/v1/signup", body.toString(), null, null);
                if (!r.ok()) { post(cb, false, errorMessage(r)); return; }
                JSONObject json = new JSONObject(r.body);
                if (json.optString("access_token").isEmpty()) {
                    post(cb, true, "Аккаунт создан. Подтверди email и затем войди.");
                    return;
                }
                saveSession(json);
                syncAccountInternal("signup");
                post(cb, true, "GRXT Auth создан · " + grxtId());
            } catch (Exception e) {
                post(cb, false, shortError(e));
            }
        });
    }

    void signIn(String email, String password, Callback cb) {
        if (!valid(email, password, cb)) return;
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("email", email.trim()).put("password", password);
                Response r = request("POST", "/auth/v1/token?grant_type=password", body.toString(), null, null);
                if (!r.ok()) { post(cb, false, errorMessage(r)); return; }
                saveSession(new JSONObject(r.body));
                syncAccountInternal("login");
                post(cb, true, "Вход выполнен · " + grxtId());
            } catch (Exception e) {
                post(cb, false, shortError(e));
            }
        });
    }

    void restore(Callback cb) {
        if (!isSignedIn()) { post(cb, true, "Гостевой режим"); return; }
        long expires = prefs.getLong(K_EXPIRES, 0L);
        if (System.currentTimeMillis() / 1000L < expires - 60) {
            io.execute(() -> {
                syncAccountInternal("restore");
                post(cb, true, "Сессия GRXT Auth восстановлена");
            });
            return;
        }
        String refresh = refreshToken();
        if (refresh.isEmpty()) {
            clear();
            post(cb, false, "Сессия истекла. Войди снова.");
            return;
        }
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("refresh_token", refresh);
                Response r = request("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), null, null);
                if (!r.ok()) {
                    clear();
                    post(cb, false, "Сессия истекла. Войди снова.");
                    return;
                }
                saveSession(new JSONObject(r.body));
                syncAccountInternal("refresh");
                post(cb, true, "Сессия обновлена");
            } catch (Exception e) {
                post(cb, false, shortError(e));
            }
        });
    }

    void signOut(Callback cb) {
        String token = accessToken();
        io.execute(() -> {
            try {
                if (!token.isEmpty()) request("POST", "/auth/v1/logout", "{}", token, null);
            } catch (Exception ignored) {
            } finally {
                clear();
                post(cb, true, "Выход выполнен");
            }
        });
    }

    void syncNow(Callback cb) {
        if (!isSignedIn()) { post(cb, false, "Сначала войди в GRXT Auth"); return; }
        io.execute(() -> {
            boolean ok = syncAccountInternal("manual_sync");
            post(cb, ok, ok ? "Данные синхронизированы" : "Auth работает, но таблицы Supabase ещё не настроены");
        });
    }

    void close() {
        io.shutdownNow();
    }

    private boolean syncAccountInternal(String event) {
        try {
            String token = accessToken();
            if (token.isEmpty()) return false;

            JSONObject profile = new JSONObject()
                    .put("id", userId())
                    .put("grxt_id", grxtId());
            Response pr = request("POST", "/rest/v1/profiles?on_conflict=id", profile.toString(), token,
                    "resolution=merge-duplicates,return=minimal");
            if (!pr.ok()) return false;

            String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            JSONObject device = new JSONObject()
                    .put("user_id", userId())
                    .put("device_key", deviceId())
                    .put("device_name", name)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android_version", Build.VERSION.RELEASE)
                    .put("app_version", appVersion())
                    .put("is_active", true);
            Response dr = request("POST", "/rest/v1/devices?on_conflict=user_id,device_key", device.toString(), token,
                    "resolution=merge-duplicates,return=minimal");
            if (!dr.ok()) return false;

            JSONObject ev = new JSONObject()
                    .put("user_id", userId())
                    .put("device_key", deviceId())
                    .put("event", event);
            request("POST", "/rest/v1/security_events", ev.toString(), token, "return=minimal");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String appVersion() {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "unknown" : version;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void saveSession(JSONObject json) throws Exception {
        String access = json.optString("access_token");
        String refresh = json.optString("refresh_token");
        long expiresIn = json.optLong("expires_in", 3600L);
        JSONObject user = json.optJSONObject("user");
        if (user == null || access.isEmpty()) throw new IllegalStateException("Supabase не вернул сессию");
        String uid = user.optString("id");
        String mail = user.optString("email");
        prefs.edit()
                .putString(K_ACCESS, access)
                .putString(K_REFRESH, refresh)
                .putString(K_USER, uid)
                .putString(K_EMAIL, mail)
                .putLong(K_EXPIRES, System.currentTimeMillis() / 1000L + expiresIn)
                .apply();
    }

    private void clear() {
        prefs.edit().clear().apply();
    }

    private boolean valid(String email, String password, Callback cb) {
        if (email == null || !email.contains("@")) {
            post(cb, false, "Введи корректный email");
            return false;
        }
        if (password == null || password.length() < 6) {
            post(cb, false, "Пароль должен быть минимум 6 символов");
            return false;
        }
        return true;
    }

    private Response request(String method, String path, String body, String bearer, String prefer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SupabaseConfig.URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestProperty("apikey", SupabaseConfig.ANON_KEY);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        else c.setRequestProperty("Authorization", "Bearer " + SupabaseConfig.ANON_KEY);
        if (prefer != null) c.setRequestProperty("Prefer", prefer);
        if (body != null) {
            c.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();
        return new Response(code, text);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String errorMessage(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String m = j.optString("msg");
            if (m.isEmpty()) m = j.optString("message");
            if (m.isEmpty()) m = j.optString("error_description");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {}
        return "Supabase HTTP " + r.code;
    }

    private static String formatGrxtId(String userId) {
        String h = sha256("GRXT:" + userId).substring(0, 8).toUpperCase(Locale.ROOT);
        return "GRXT-" + h.substring(0, 4) + "-" + h.substring(4, 8);
    }

    private static String sha256(String value) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : h) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }

    private void post(Callback cb, boolean ok, String message) {
        main.post(() -> cb.done(ok, message));
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
        boolean ok() { return code >= 200 && code < 300; }
    }
}
