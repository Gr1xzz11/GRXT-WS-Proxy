package com.grxt.wsproxy;

import android.util.Log;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Socks5ProxyEngine implements Closeable {
    interface Listener { void onState(String state, String route); }

    private static final String TAG = "GRXTSocksCore";
    private static final int HANDSHAKE_LEN = 64;
    private static final int ABRIDGED = 0xEFEFEFEF;
    private static final int INTERMEDIATE = 0xEEEEEEEE;
    private static final int PADDED = 0xDDDDDDDD;
    private static final SecureRandom RNG = new SecureRandom();

    private static final String[] CF_BASE_DOMAINS = {
            "pclead.co.uk", "offshor.co.uk", "cakeisalie.co.uk", "noskomnadzor.co.uk",
            "lovetrue.co.uk", "sorokdva.co.uk", "pyatdesyatdva.co.uk", "kartoshka.co.uk",
            "sorokodin.co.uk", "pyatdesyatodin.co.uk", "notelega.co.uk", "ebally.co.uk",
            "nebally.co.uk", "havegreatday.co.uk", "pomogite.co.uk", "fixtelega.co.uk",
            "sadnews.co.uk", "onedaychamp.co.uk", "stopblocking.co.uk", "nothingthere.co.uk"
    };

    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;

    Socks5ProxyEngine(Listener listener) {
        this.listener = listener;
    }

    void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), Settings.PORT));
        running = true;
        listener.onState("running", "SOCKS5 · локальный порт готов");
        pool.execute(this::acceptLoop);
        pool.execute(this::probeRoute);
    }

    boolean isRunning() { return running; }

    private void probeRoute() {
        try (RawWebSocket ws = tryDirectTelegramWs(4, false, 3000)) {
            if (ws != null) {
                listener.onState("running", "Auto · Telegram WebSocket готов");
                return;
            }
        } catch (Exception ignored) {}
        try (RawWebSocket ws = tryCloudflareWs(4, 2500, 5)) {
            if (ws != null) {
                listener.onState("running", "Auto · Cloudflare WebSocket готов");
                return;
            }
        } catch (Exception ignored) {}
        listener.onState("running", "Auto · WebSocket недоступен, есть TCP fallback");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket client) {
        Target target = null;
        try (Socket c = client) {
            c.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = new BufferedOutputStream(c.getOutputStream());

            target = socksHandshake(in, out);
            byte[] init = readExact(in, HANDSHAKE_LEN);
            c.setSoTimeout(0);

            ParsedInit parsed = parseInit(init);
            if (parsed == null) {
                listener.onState("running", "TCP passthrough · " + target.host);
                bridgeDirectTcp(in, out, target, init);
                return;
            }

            int dc = Math.abs(parsed.dcIndex);
            boolean media = parsed.dcIndex < 0;
            if (dc < 1 || dc > 5) {
                listener.onState("running", "TCP passthrough · неизвестный DC");
                bridgeDirectTcp(in, out, target, init);
                return;
            }

            PacketSplitter splitter = new PacketSplitter(init, parsed.protocol);
            RawWebSocket ws = tryDirectTelegramWs(dc, media, 3200);
            String route = ws == null ? null : "Telegram WS · DC" + dc;

            if (ws == null) {
                listener.onState("running", "Cloudflare · поиск DC" + dc);
                ws = tryCloudflareWs(dc, 2800, 8);
                if (ws != null) route = "Cloudflare WS · DC" + dc;
            }

            if (ws != null) {
                listener.onState("running", route);
                try (RawWebSocket active = ws) {
                    active.sendBinary(init);
                    bridgeWebSocket(in, out, active, splitter, route);
                }
                return;
            }

            listener.onState("running", "TCP fallback · DC" + dc);
            bridgeDirectTcp(in, out, target, init);
        } catch (Exception e) {
            String t = target == null ? "?" : target.host + ":" + target.port;
            Log.w(TAG, "session " + t + " closed: " + e, e);
            if (running) listener.onState("running", "Готов · новая сессия при следующем подключении");
        }
    }

    private Target socksHandshake(InputStream in, OutputStream out) throws IOException {
        int version = readU8(in);
        if (version != 5) throw new IOException("SOCKS version " + version + " not supported");
        int methods = readU8(in);
        byte[] offered = readExact(in, methods);
        boolean noAuth = false;
        for (byte b : offered) if ((b & 0xff) == 0) noAuth = true;
        if (!noAuth) {
            out.write(new byte[]{5, (byte)0xff}); out.flush();
            throw new IOException("SOCKS no-auth not offered");
        }
        out.write(new byte[]{5, 0}); out.flush();

        if (readU8(in) != 5) throw new IOException("Bad SOCKS request version");
        int cmd = readU8(in);
        readU8(in); // reserved
        if (cmd != 1) throw new IOException("Only SOCKS CONNECT is supported");
        int atyp = readU8(in);
        String host;
        if (atyp == 1) {
            byte[] a = readExact(in, 4);
            host = InetAddress.getByAddress(a).getHostAddress();
        } else if (atyp == 3) {
            int n = readU8(in);
            host = new String(readExact(in, n), java.nio.charset.StandardCharsets.UTF_8);
        } else if (atyp == 4) {
            byte[] a = readExact(in, 16);
            host = InetAddress.getByAddress(a).getHostAddress();
        } else {
            throw new IOException("Unsupported SOCKS address type " + atyp);
        }
        int port = (readU8(in) << 8) | readU8(in);

        // Local proxy: acknowledge immediately so Telegram can send its MTProto init,
        // which is required to choose the optimal WebSocket route.
        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte)(Settings.PORT >>> 8), (byte)Settings.PORT});
        out.flush();
        return new Target(host, port);
    }

    private ParsedInit parseInit(byte[] init) {
        try {
            byte[] key = Arrays.copyOfRange(init, 8, 40);
            byte[] iv = Arrays.copyOfRange(init, 40, 56);
            Cipher c = aesCtr(key, iv);
            byte[] plain = c.update(init);
            int proto = ByteBuffer.wrap(plain, 56, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (proto != ABRIDGED && proto != INTERMEDIATE && proto != PADDED) return null;
            short dc = ByteBuffer.wrap(plain, 60, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            return new ParsedInit(dc, proto);
        } catch (Exception e) {
            return null;
        }
    }

    private RawWebSocket tryDirectTelegramWs(int dc, boolean media, int timeoutMs) {
        if (dc != 2 && dc != 4) return null;
        String target = "149.154.167.220";
        String[] domains = media
                ? new String[]{"kws" + dc + "-1.web.telegram.org", "kws" + dc + ".web.telegram.org"}
                : new String[]{"kws" + dc + ".web.telegram.org", "kws" + dc + "-1.web.telegram.org"};
        for (String domain : domains) {
            if (!running) return null;
            try { return RawWebSocket.connect(target, domain, timeoutMs, "/apiws"); }
            catch (IOException e) { Log.d(TAG, "direct WS " + domain + ": " + e.getMessage()); }
        }
        return null;
    }

    private RawWebSocket tryCloudflareWs(int dc, int timeoutMs, int maxAttempts) {
        List<String> bases = new ArrayList<>(Arrays.asList(CF_BASE_DOMAINS));
        Collections.shuffle(bases, RNG);
        for (int i = 0; i < Math.min(maxAttempts, bases.size()); i++) {
            if (!running) return null;
            String domain = "kws" + dc + "." + bases.get(i);
            try { return RawWebSocket.connect(domain, domain, timeoutMs, "/apiws"); }
            catch (IOException e) { Log.d(TAG, "CF WS " + domain + ": " + e.getMessage()); }
        }
        return null;
    }

    private void bridgeWebSocket(InputStream clientIn, OutputStream clientOut,
                                 RawWebSocket ws, PacketSplitter splitter, String route) throws Exception {
        final long[] upBytes = {0};
        final long[] downBytes = {0};
        Future<?> up = pool.submit(() -> {
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = clientIn.read(buf)) >= 0) {
                    if (n == 0) continue;
                    upBytes[0] += n;
                    byte[] chunk = Arrays.copyOf(buf, n);
                    List<byte[]> packets = splitter.feed(chunk);
                    for (byte[] packet : packets) ws.sendBinary(packet);
                }
                for (byte[] packet : splitter.flush()) ws.sendBinary(packet);
            } catch (Exception e) {
                Log.d(TAG, "client->ws ended: " + e.getMessage());
            }
        });
        Future<?> down = pool.submit(() -> {
            try {
                byte[] data;
                while ((data = ws.receiveBinary()) != null) {
                    downBytes[0] += data.length;
                    synchronized (clientOut) {
                        clientOut.write(data);
                        clientOut.flush();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "ws->client ended: " + e.getMessage());
            }
        });
        waitEither(up, down);
        up.cancel(true);
        down.cancel(true);
        Log.i(TAG, route + " closed up=" + upBytes[0] + " down=" + downBytes[0]);
    }

    private void bridgeDirectTcp(InputStream clientIn, OutputStream clientOut,
                                 Target target, byte[] initial) throws Exception {
        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(target.host, target.port), 6000);
            remote.setTcpNoDelay(true);
            remote.setKeepAlive(true);
            InputStream remoteIn = new BufferedInputStream(remote.getInputStream());
            OutputStream remoteOut = new BufferedOutputStream(remote.getOutputStream());
            remoteOut.write(initial);
            remoteOut.flush();

            Future<?> up = pool.submit(() -> copy(clientIn, remoteOut));
            Future<?> down = pool.submit(() -> copy(remoteIn, clientOut));
            waitEither(up, down);
            up.cancel(true);
            down.cancel(true);
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                synchronized (out) { out.write(buf, 0, n); out.flush(); }
            }
        } catch (Exception ignored) {}
    }

    private static void waitEither(Future<?> a, Future<?> b) throws InterruptedException {
        while (!a.isDone() && !b.isDone()) Thread.sleep(40);
    }

    @Override public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        listener.onState("stopped", "off");
    }

    private static Cipher aesCtr(byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    private static int readU8(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int p = 0;
        while (p < n) {
            int r = in.read(b, p, n - p);
            if (r < 0) throw new EOFException();
            p += r;
        }
        return b;
    }

    private static final class Target {
        final String host; final int port;
        Target(String h, int p) { host = h; port = p; }
    }

    private static final class ParsedInit {
        final short dcIndex; final int protocol;
        ParsedInit(short dc, int p) { dcIndex = dc; protocol = p; }
    }

    private static final class PacketSplitter {
        private final Cipher inspect;
        private final int proto;
        private byte[] cipherBuf = new byte[0];
        private byte[] plainBuf = new byte[0];
        private boolean disabled;

        PacketSplitter(byte[] init, int proto) throws Exception {
            inspect = aesCtr(Arrays.copyOfRange(init, 8, 40), Arrays.copyOfRange(init, 40, 56));
            inspect.update(new byte[64]);
            this.proto = proto;
        }

        synchronized List<byte[]> feed(byte[] chunk) throws Exception {
            if (chunk.length == 0) return Collections.emptyList();
            if (disabled) return Collections.singletonList(chunk);
            cipherBuf = concat(cipherBuf, chunk);
            plainBuf = concat(plainBuf, inspect.update(chunk));
            List<byte[]> out = new ArrayList<>();
            int offset = 0;
            while (offset < cipherBuf.length) {
                Integer len = nextLen(offset, cipherBuf.length - offset);
                if (len == null) break;
                if (len <= 0) {
                    out.add(Arrays.copyOfRange(cipherBuf, offset, cipherBuf.length));
                    offset = cipherBuf.length;
                    disabled = true;
                    break;
                }
                out.add(Arrays.copyOfRange(cipherBuf, offset, offset + len));
                offset += len;
            }
            if (offset > 0) {
                cipherBuf = Arrays.copyOfRange(cipherBuf, offset, cipherBuf.length);
                plainBuf = Arrays.copyOfRange(plainBuf, offset, plainBuf.length);
            }
            return out;
        }

        synchronized List<byte[]> flush() {
            if (cipherBuf.length == 0) return Collections.emptyList();
            byte[] result = cipherBuf;
            cipherBuf = new byte[0]; plainBuf = new byte[0];
            return Collections.singletonList(result);
        }

        private Integer nextLen(int off, int avail) {
            if (proto == ABRIDGED) {
                if (avail < 1) return null;
                int first = plainBuf[off] & 0xff;
                int payload, header;
                if (first == 0x7f || first == 0xff) {
                    if (avail < 4) return null;
                    payload = ((plainBuf[off + 1] & 255) |
                            ((plainBuf[off + 2] & 255) << 8) |
                            ((plainBuf[off + 3] & 255) << 16)) * 4;
                    header = 4;
                } else {
                    payload = (first & 0x7f) * 4;
                    header = 1;
                }
                if (payload <= 0) return 0;
                return avail < header + payload ? null : header + payload;
            }
            if (proto == INTERMEDIATE || proto == PADDED) {
                if (avail < 4) return null;
                int payload = ByteBuffer.wrap(plainBuf, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0x7fffffff;
                if (payload <= 0) return 0;
                return avail < 4 + payload ? null : 4 + payload;
            }
            return 0;
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }
}
