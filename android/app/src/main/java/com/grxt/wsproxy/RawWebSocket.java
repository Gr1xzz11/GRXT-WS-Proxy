package com.grxt.wsproxy;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class RawWebSocket implements Closeable {
    private static final SecureRandom RNG = new SecureRandom();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private RawWebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    static RawWebSocket connect(String targetHost, String domain, int timeoutMs) throws IOException {
        return connect(targetHost, domain, timeoutMs, "/apiws");
    }

    static RawWebSocket connect(String targetHost, String domain, int timeoutMs, String path) throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new java.net.InetSocketAddress(targetHost, 443), timeoutMs);
        raw.setSoTimeout(timeoutMs);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, domain, 443, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setServerNames(java.util.Collections.singletonList(new SNIHostName(domain)));
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();

        RawWebSocket ws = new RawWebSocket(ssl);
        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + domain + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Protocol: binary\r\n\r\n";
        synchronized (ws.writeLock) {
            ws.out.write(request.getBytes(StandardCharsets.US_ASCII));
            ws.out.flush();
        }

        String status = readAsciiLine(ws.in);
        if (status == null || !status.contains(" 101 ")) {
            ws.close();
            throw new IOException("WebSocket handshake failed: " + status);
        }
        String line;
        while ((line = readAsciiLine(ws.in)) != null && !line.isEmpty()) {}
        ssl.setSoTimeout(0);
        return ws;
    }

    void sendBinary(byte[] data) throws IOException {
        synchronized (writeLock) {
            if (closed) throw new EOFException("WebSocket closed");
            writeFrameLocked(0x2, data);
        }
    }

    byte[] receiveBinary() throws IOException {
        while (!closed) {
            int a = in.read();
            int b = in.read();
            if (a < 0 || b < 0) throw new EOFException();
            int opcode = a & 0x0f;
            long len = b & 0x7f;
            if (len == 126) len = ((long) readU8() << 8) | readU8();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readU8();
            }
            if (len > Integer.MAX_VALUE) throw new IOException("WS frame too large");
            byte[] mask = null;
            if ((b & 0x80) != 0) mask = readExact(4);
            byte[] payload = readExact((int) len);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            if (opcode == 0x8) { closed = true; return null; }
            if (opcode == 0x9) {
                synchronized (writeLock) {
                    if (!closed) writeFrameLocked(0xA, payload);
                }
                continue;
            }
            if (opcode == 0xA) continue;
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) return payload;
        }
        return null;
    }

    private void writeFrameLocked(int opcode, byte[] data) throws IOException {
        out.write(0x80 | opcode);
        int len = data.length;
        if (len < 126) out.write(0x80 | len);
        else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(0x80 | 127);
            long v = len;
            for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8)) & 0xff);
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        out.write(mask);
        for (int i = 0; i < data.length; i++) out.write(data[i] ^ mask[i & 3]);
        out.flush();
    }

    private int readU8() throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private byte[] readExact(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n') {
                byte[] data = buf.toByteArray();
                int n = data.length;
                if (n > 0 && data[n - 1] == '\r') n--;
                return new String(data, 0, n, StandardCharsets.US_ASCII);
            }
            buf.write(c);
            prev = c;
            if (buf.size() > 16384) throw new IOException("HTTP header too large");
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII.name());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
