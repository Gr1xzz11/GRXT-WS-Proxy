package com.grxt.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GrxtAuth {
    interface Callback {
        void done(boolean ok, String message);
    }

    static final String AUTH_REDIRECT = "grxt://auth/confirmed";

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

    String email() { return value(K_EMAIL); }
    String userId() { return value(K_USER); }
    String accessToken() { return value(K_ACCESS); }
    String refreshToken() { return value(K_REFRESH); }

    private String value(String key) {
        String v = prefs.getString(key, "");
        return v == null ? "" : v;
    }

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
        final String mail = email.trim();
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("email", mail).put("password", password);
                String redirect = URLEncoder.encode(AUTH_REDIRECT, StandardCharsets.UTF_8.name());
                Response r = request("POST", "/auth/v1/signup?redirect_to=" + redirect, body.toString(), null, null);
                if (!r.ok()) {
                    post(cb, false, "Регистрация не создана: " + errorMessage(r));
                    return;
                }

                JSONObject json = new JSONObject(r.body);
                String access = json.optString("access_token");
                if (!access.isEmpty()) {
                    saveSession(json);
                    syncAccountInternal("signup");
                    post(cb, true, "GRXT Auth создан · " + grxtId());
                    return;
                }

                JSONObject user = json.optJSONObject("user");
                if (user == null || user.optString("id").isEmpty()) {
                    post(cb, false, "Supabase ответил OK, но пользователя не создал. Проверь Email provider и Auth logs в Supabase.");
                    return;
                }

                JSONArray identities = user.optJSONArray("identities");
                if (identities != null && identities.length() == 0) {
                    post(cb, false, "Supabase не создал новую учётку. Скорее всего этот email уже зарегистрирован. Попробуй войти.");
                    return;
                }

                // Verify that the returned signup really exists in Auth. For an unconfirmed account
                // Supabase normally rejects password sign-in with email_not_confirmed.
                Response verify = request("POST", "/auth/v1/token?grant_type=password", body.toString(), null, null);
                if (verify.ok()) {
                    saveSession(new JSONObject(verify.body));
                    syncAccountInternal("signup_verified");
                    post(cb, true, "GRXT Auth создан · " + grxtId());
                    return;
                }

                String verifyError = errorMessage(verify);
                String verifyCode = errorCode(verify);
                String lower = (verifyCode + " " + verifyError).toLowerCase(Locale.ROOT);
                if (lower.contains("email_not_confirmed") || lower.contains("email not confirmed") || lower.contains("not confirmed")) {
                    String uid = user.optString("id");
                    String shortUid = uid.length() > 8 ? uid.substring(0, 8) : uid;
                    post(cb, true, "Пользователь реально создан в Supabase Auth (" + shortUid + "…). Ожидается письмо подтверждения. Если письма нет — жми «ПОВТОРИТЬ ПИСЬМО» и проверь SMTP/Auth Logs.");
                    return;
                }

                post(cb, false, "Signup вернул пользователя, но Auth-проверка не прошла: " + verifyError);
            } catch (Exception e) {
                post(cb, false, shortError(e));
            }
        });
    }

    void resendConfirmation(String email, Callback cb) {
        if (email == null || !email.contains("@")) {
            post(cb, false, "Введи email для повторной отправки");
            return;
        }
        final String mail = email.trim();
        io.execute(() -> {
            try {
                String redirect = URLEncoder.encode(AUTH_REDIRECT, StandardCharsets.UTF_8.name());
                JSONObject body = new JSONObject().put("type", "signup").put("email", mail);
                Response r = request("POST", "/auth/v1/resend?redirect_to=" + redirect, body.toString(), null, null);
                if (!r.ok()) {
                    post(cb, false, "Supabase не принял повторное письмо: " + errorMessage(r));
                    return;
                }
                post(cb, true, "Supabase принял запрос на повторное письмо. Если его нет во Входящих/Спаме — проверь SMTP и Auth Logs в проекте.");
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

    void consumeAuthRedirect(Uri uri, Callback cb) {
        if (uri == null || !"grxt".equalsIgnoreCase(uri.getScheme()) || !"auth".equalsIgnoreCase(uri.getHost())) {
            post(cb, false, "Некорректная ссылка GRXT Auth");
            return;
        }

        Map<String, String> params = new HashMap<>();
        params.putAll(parseParams(uri.getEncodedQuery()));
        params.putAll(parseParams(uri.getEncodedFragment()));

        String error = firstNonEmpty(params.get("error_description"), params.get("error"));
        if (!error.isEmpty()) {
            post(cb, false, error.replace('+', ' '));
            return;
        }

        String access = mapValue(params, "access_token");
        String refresh = mapValue(params, "refresh_token");
        if (access.isEmpty()) {
            post(cb, false, "Supabase не вернул сессию после подтверждения. Запроси новое письмо и попробуй ещё раз.");
            return;
        }

        long expiresIn = 3600L;
        try { expiresIn = Long.parseLong(mapValue(params, "expires_in")); }
        catch (Exception ignored) {}
        final long finalExpiresIn = expiresIn;

        io.execute(() -> {
            try {
                Response userResponse = request("GET", "/auth/v1/user", null, access, null);
                if (!userResponse.ok()) {
                    post(cb, false, errorMessage(userResponse));
                    return;
                }
                JSONObject session = new JSONObject()
                        .put("access_token", access)
                        .put("refresh_token", refresh)
                        .put("expires_in", finalExpiresIn)
                        .put("user", new JSONObject(userResponse.body));
                saveSession(session);
                syncAccountInternal("email_confirmed");
                post(cb, true, "Email подтверждён · " + grxtId());
            } catch (Exception e) {
                post(cb, false, shortError(e));
            }
        });
    }

    void restore(Callback cb) {
        if (!isSignedIn()) { post(cb, false, "Требуется вход в GRXT Auth"); return; }
        long expires = prefs.getLong(K_EXPIRES, 0L);
        if (System.currentTimeMillis() / 1000L < expires - 60) {
            io.execute(() -> {
                boolean synced = syncAccountInternal("restore");
                post(cb, true, synced ? "Сессия GRXT Auth восстановлена" : "Сессия восстановлена, но grxt_users ещё не настроена");
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
            post(cb, ok, ok ? "grxt_users синхронизирована" : "Auth работает, но unified migration 0002 ещё не применена");
        });
    }

    void close() {
        io.shutdownNow();
    }

    private boolean syncAccountInternal(String event) {
        try {
            String token = accessToken();
            if (token.isEmpty()) return false;

            String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
            JSONObject body = new JSONObject()
                    .put("p_device_key", deviceId())
                    .put("p_device_name", name)
                    .put("p_manufacturer", Build.MANUFACTURER)
                    .put("p_model", Build.MODEL)
                    .put("p_android_version", Build.VERSION.RELEASE)
                    .put("p_app_version", appVersion())
                    .put("p_event", event);

            Response r = request("POST", "/rest/v1/rpc/grxt_sync_user", body.toString(), token, null);
            return r.ok();
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
        if (uid.isEmpty()) throw new IllegalStateException("Supabase не вернул user.id");
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

    private static Map<String, String> parseParams(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String key = i >= 0 ? pair.substring(0, i) : pair;
            String val = i >= 0 ? pair.substring(i + 1) : "";
            out.put(Uri.decode(key), Uri.decode(val));
        }
        return out;
    }

    private static String mapValue(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? "" : v;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
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

    private static String errorCode(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String code = j.optString("error_code");
            if (code.isEmpty()) code = j.optString("code");
            return code;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String errorMessage(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String m = j.optString("msg");
            if (m.isEmpty()) m = j.optString("message");
            if (m.isEmpty()) m = j.optString("error_description");
            if (m.isEmpty()) m = j.optString("error");
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
        return m.length() > 180 ? m.substring(0, 180) + "…" : m;
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
