package com.grxt.wsproxy;

import android.content.Context;

public final class Settings {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 1080;

    private Settings() {}

    public static String telegramLink(Context context) {
        return "tg://socks?server=" + HOST + "&port=" + PORT;
    }
}
