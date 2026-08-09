package com.grxt.wsproxy;

import android.util.Log;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class MtProtoProxyEngine implements Closeable {
    interface Listener { void onState(String state, String route); }

    private static final String TAG = "GRXTProxyCore";
    private static final int HANDSHAKE_LEN = 64;
    private static final int ABRIDGED = 0xEFEFEFEF;
    private static final int INTERMEDIATE = 0xEEEEEEEE;
    private static final int PADDED = 0xDDDDDDDD;
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] secret;
    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;

    MtProtoProxyEngine(String secretHex, Listener listener) {
        this.secret = hex(secretHex);
        this.listener = listener;
    }

    void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), Settings.PORT));
        running = true;
        listener.onState("running", "waiting");
        pool.execute(this::acceptLoop);
    }

    boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket client) {
        String route = "none";
        try (Socket c = client) {
            InputStream cin = new BufferedInputStream(c.getInputStream());
            OutputStream cout = new BufferedOutputStream(c.getOutputStream());
            byte[] init = readExact(cin, HANDSHAKE_LEN);
            Handshake hs = parseHandshake(init);
            if (hs == null) throw new IOException("bad MTProto handshake/secret");

            byte[] relayInit = generateRelayInit(hs.protoTag, hs.dcIndex);
            CryptoContext crypto = buildCrypto(init, relayInit);
            PacketSplitter splitter = new PacketSplitter(relayInit, hs.protocol);
            int dc = Math.abs(hs.dcIndex);
            boolean media = hs.dcIndex < 0;

            RawWebSocket ws = null;
            if (dc == 2 || dc == 4) {
                String target = "149.154.167.220";
                String[] domains = media
                        ? new String[]{"kws" + dc + "-1.web.telegram.org", "kws" + dc + ".web.telegram.org"}
                        : new String[]{"kws" + dc + ".web.telegram.org", "kws" + dc + "-1.web.telegram.org"};
                for (String domain : domains) {
                    try {
                        ws = RawWebSocket.connect(target, domain, 6000);
                        route = "WebSocket / " + domain;
                        break;
                    } catch (IOException e) {
                        Log.w(TAG, "WS failed " + domain + ": " + e.getMessage());
                    }
                }
            }

            if (ws != null) {
                listener.onState("running", route);
                ws.sendBinary(relayInit);
                bridgeWebSocket(cin, cout, ws, crypto, splitter);
            } else {
                String ip = fallbackIp(dc);
                if (ip == null) throw new IOException("No route for DC" + dc);
                route = "TCP fallback / DC" + dc;
                listener.onState("running", route);
                bridgeTcp(cin, cout, ip, relayInit, crypto);
            }
        } catch (Exception e) {
            Log.w(TAG, "session closed: " + e.getMessage());
            listener.onState(running ? "running" : "stopped", route + " / " + e.getClass().getSimpleName());
        }
    }

    private void bridgeWebSocket(InputStream cin, OutputStream cout, RawWebSocket ws,
                                 CryptoContext crypto, PacketSplitter splitter) throws Exception {
        Future<?> up = pool.submit(() -> {
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = cin.read(buf)) >= 0) {
                    if (n == 0) continue;
                    byte[] chunk = Arrays.copyOf(buf, n);
                    byte[] plain = crypto.clientDec.update(chunk);
                    byte[] telegramCipher = crypto.telegramEnc.update(plain);
                    for (byte[] packet : splitter.feed(telegramCipher)) ws.sendBinary(packet);
                }
                for (byte[] packet : splitter.flush()) ws.sendBinary(packet);
            } catch (Exception ignored) {}
        });
        Future<?> down = pool.submit(() -> {
            try {
                byte[] data;
                while ((data = ws.receiveBinary()) != null) {
                    byte[] plain = crypto.telegramDec.update(data);
                    byte[] clientCipher = crypto.clientEnc.update(plain);
                    synchronized (cout) { cout.write(clientCipher); cout.flush(); }
                }
            } catch (Exception ignored) {}
        });
        waitEither(up, down);
        up.cancel(true); down.cancel(true); ws.close();
    }

    private void bridgeTcp(InputStream cin, OutputStream cout, String ip,
                           byte[] relayInit, CryptoContext crypto) throws Exception {
        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(ip, 443), 8000);
            remote.setTcpNoDelay(true);
            InputStream rin = new BufferedInputStream(remote.getInputStream());
            OutputStream rout = new BufferedOutputStream(remote.getOutputStream());
            rout.write(relayInit); rout.flush();

            Future<?> up = pool.submit(() -> forwardTcp(cin, rout, crypto.clientDec, crypto.telegramEnc));
            Future<?> down = pool.submit(() -> forwardTcp(rin, cout, crypto.telegramDec, crypto.clientEnc));
            waitEither(up, down); up.cancel(true); down.cancel(true);
        }
    }

    private static void forwardTcp(InputStream in, OutputStream out, Cipher dec, Cipher enc) {
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                byte[] plain = dec.update(buf, 0, n);
                byte[] crypt = enc.update(plain);
                synchronized (out) { out.write(crypt); out.flush(); }
            }
        } catch (Exception ignored) {}
    }

    private static void waitEither(Future<?> a, Future<?> b) throws InterruptedException {
        while (!a.isDone() && !b.isDone()) Thread.sleep(50);
    }

    private Handshake parseHandshake(byte[] handshake) throws Exception {
        byte[] prekey = Arrays.copyOfRange(handshake, 8, 40);
        byte[] iv = Arrays.copyOfRange(handshake, 40, 56);
        Cipher cipher = aesCtr(sha256(concat(prekey, secret)), iv);
        byte[] decrypted = cipher.update(handshake);
        int proto = ByteBuffer.wrap(decrypted, 56, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (proto != ABRIDGED && proto != INTERMEDIATE && proto != PADDED) return null;
        short dc = ByteBuffer.wrap(decrypted, 60, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
        return new Handshake(Arrays.copyOfRange(decrypted, 56, 60), dc, proto);
    }

    private byte[] generateRelayInit(byte[] protoTag, short dcIndex) throws Exception {
        byte[] rnd = new byte[64];
        while (true) {
            RNG.nextBytes(rnd);
            if ((rnd[0] & 0xff) == 0xef) continue;
            byte[] first4 = Arrays.copyOfRange(rnd, 0, 4);
            if (Arrays.equals(first4, "HEAD".getBytes()) || Arrays.equals(first4, "POST".getBytes()) ||
                    Arrays.equals(first4, "GET ".getBytes()) || Arrays.equals(first4, new byte[]{(byte)0xee,(byte)0xee,(byte)0xee,(byte)0xee}) ||
                    Arrays.equals(first4, new byte[]{(byte)0xdd,(byte)0xdd,(byte)0xdd,(byte)0xdd})) continue;
            if (rnd[4] == 0 && rnd[5] == 0 && rnd[6] == 0 && rnd[7] == 0) continue;
            break;
        }
        byte[] key = Arrays.copyOfRange(rnd, 8, 40);
        byte[] iv = Arrays.copyOfRange(rnd, 40, 56);
        Cipher c = aesCtr(key, iv);
        byte[] encrypted = c.update(rnd);
        byte[] tail = new byte[8];
        System.arraycopy(protoTag, 0, tail, 0, 4);
        ByteBuffer.wrap(tail, 4, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIndex);
        byte[] result = Arrays.copyOf(rnd, 64);
        for (int i = 0; i < 8; i++) {
            byte keystream = (byte) (encrypted[56 + i] ^ rnd[56 + i]);
            result[56 + i] = (byte) (tail[i] ^ keystream);
        }
        return result;
    }

    private CryptoContext buildCrypto(byte[] clientInit, byte[] relayInit) throws Exception {
        byte[] clientPrekeyIv = Arrays.copyOfRange(clientInit, 8, 56);
        byte[] prekey = Arrays.copyOfRange(clientPrekeyIv, 0, 32);
        byte[] iv = Arrays.copyOfRange(clientPrekeyIv, 32, 48);
        Cipher clientDec = aesCtr(sha256(concat(prekey, secret)), iv); clientDec.update(new byte[64]);

        byte[] reversed = reverse(clientPrekeyIv);
        Cipher clientEnc = aesCtr(sha256(concat(Arrays.copyOfRange(reversed, 0, 32), secret)), Arrays.copyOfRange(reversed, 32, 48));

        Cipher telegramEnc = aesCtr(Arrays.copyOfRange(relayInit, 8, 40), Arrays.copyOfRange(relayInit, 40, 56));
        telegramEnc.update(new byte[64]);
        byte[] relayReverse = reverse(Arrays.copyOfRange(relayInit, 8, 56));
        Cipher telegramDec = aesCtr(Arrays.copyOfRange(relayReverse, 0, 32), Arrays.copyOfRange(relayReverse, 32, 48));
        return new CryptoContext(clientDec, clientEnc, telegramEnc, telegramDec);
    }

    private static String fallbackIp(int dc) {
        switch (dc) {
            case 1: return "149.154.175.50";
            case 2: return "149.154.167.51";
            case 3: return "149.154.175.100";
            case 4: return "149.154.167.91";
            case 5: return "149.154.171.5";
            default: return null;
        }
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
    private static byte[] sha256(byte[] in) throws Exception { return MessageDigest.getInstance("SHA-256").digest(in); }
    private static byte[] concat(byte[] a, byte[] b) { byte[] o = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b,0,o,a.length,b.length); return o; }
    private static byte[] reverse(byte[] a) { byte[] o = new byte[a.length]; for (int i=0;i<a.length;i++) o[i]=a[a.length-1-i]; return o; }
    private static byte[] readExact(InputStream in, int n) throws IOException { byte[] b=new byte[n]; int p=0,r; while(p<n&&(r=in.read(b,p,n-p))>=0)p+=r; if(p!=n)throw new EOFException(); return b; }
    private static byte[] hex(String s) { byte[] out=new byte[s.length()/2]; for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return out; }

    private static final class Handshake {
        final byte[] protoTag; final short dcIndex; final int protocol;
        Handshake(byte[] p, short d, int pr) { protoTag=p; dcIndex=d; protocol=pr; }
    }
    private static final class CryptoContext {
        final Cipher clientDec, clientEnc, telegramEnc, telegramDec;
        CryptoContext(Cipher a,Cipher b,Cipher c,Cipher d){clientDec=a;clientEnc=b;telegramEnc=c;telegramDec=d;}
    }

    private static final class PacketSplitter {
        private final Cipher inspect;
        private final int proto;
        private byte[] cipherBuf = new byte[0];
        private byte[] plainBuf = new byte[0];
        PacketSplitter(byte[] relayInit, int proto) throws Exception {
            inspect = aesCtr(Arrays.copyOfRange(relayInit,8,40), Arrays.copyOfRange(relayInit,40,56));
            inspect.update(new byte[64]); this.proto=proto;
        }
        synchronized List<byte[]> feed(byte[] chunk) throws Exception {
            cipherBuf = concat(cipherBuf, chunk); plainBuf = concat(plainBuf, inspect.update(chunk));
            List<byte[]> out = new ArrayList<>(); int offset=0;
            while (offset < cipherBuf.length) {
                Integer len = nextLen(offset, cipherBuf.length-offset);
                if (len == null) break;
                if (len <= 0) { out.add(Arrays.copyOfRange(cipherBuf, offset, cipherBuf.length)); offset=cipherBuf.length; break; }
                out.add(Arrays.copyOfRange(cipherBuf, offset, offset+len)); offset += len;
            }
            if (offset > 0) { cipherBuf=Arrays.copyOfRange(cipherBuf,offset,cipherBuf.length); plainBuf=Arrays.copyOfRange(plainBuf,offset,plainBuf.length); }
            return out;
        }
        synchronized List<byte[]> flush() { if(cipherBuf.length==0)return Collections.emptyList(); byte[] t=cipherBuf; cipherBuf=new byte[0]; plainBuf=new byte[0]; return Collections.singletonList(t); }
        private Integer nextLen(int off,int avail) {
            if (proto == ABRIDGED) {
                if (avail < 1) return null; int first=plainBuf[off]&0xff; int payload,header;
                if(first==0x7f||first==0xff){ if(avail<4)return null; payload=((plainBuf[off+1]&255)|((plainBuf[off+2]&255)<<8)|((plainBuf[off+3]&255)<<16))*4; header=4; }
                else { payload=(first&0x7f)*4; header=1; }
                if(payload<=0)return 0; return avail<header+payload?null:header+payload;
            }
            if (proto == INTERMEDIATE || proto == PADDED) {
                if(avail<4)return null; int payload=ByteBuffer.wrap(plainBuf,off,4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0x7fffffff;
                if(payload<=0)return 0; return avail<4+payload?null:4+payload;
            }
            return 0;
        }
    }
}
