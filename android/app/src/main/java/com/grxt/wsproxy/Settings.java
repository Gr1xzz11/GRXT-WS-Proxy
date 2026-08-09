package com.grxt.wsproxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class Settings {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 1443;

    private static final String PREFS = "grxt_ws_proxy";
    private static final String KEY_SECRET = "mtproto_secret_v3";

    private Settings() {}

    public static String secret(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String secret = prefs.getString(KEY_SECRET, null);
        if (secret != null && secret.matches("[0-9a-fA-F]{32}")) return secret.toLowerCase();

        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        secret = sb.toString();
        prefs.edit().putString(KEY_SECRET, secret).apply();
        return secret;
    }

    public static void regenerateSecret(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_SECRET).apply();
        secret(context);
    }

    public static String telegramLink(Context context) {
        return "tg://proxy?server=" + HOST + "&port=" + PORT + "&secret=dd" + secret(context);
    }
}
