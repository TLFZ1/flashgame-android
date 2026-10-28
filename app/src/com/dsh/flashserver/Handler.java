package com.dsh.flashserver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLDecoder;

public class Handler implements Runnable {
    private final Socket sock;
    private final File root;

    public Handler(Socket s, File root) {
        this.sock = s;
        this.root = root;
    }

    public void run() {
        try {
            InputStream in = sock.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line = r.readLine();
            if (line == null) { sock.close(); return; }
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            path = URLDecoder.decode(path, "UTF-8");
            if (path.equals("/") || path.length() == 0) path = "/index.html";
            if (path.startsWith("/")) path = path.substring(1);

            File f = new File(root, path);
            String rootPath = root.getCanonicalPath();
            String fPath = f.getCanonicalPath();
            if (!fPath.equals(rootPath) && !fPath.startsWith(rootPath + File.separator)) {
                send(sock, 403, "text/plain", "forbidden".getBytes("UTF-8"));
                return;
            }
            if (f.isDirectory()) f = new File(f, "index.html");
            if (!f.exists() || !f.isFile()) {
                send(sock, 404, "text/plain", "not found".getBytes("UTF-8"));
                return;
            }
            byte[] data = readAll(f);
            send(sock, 200, contentType(f.getName()), data);
        } catch (Exception e) {
            try { sock.close(); } catch (Exception x) { }
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
        byte[] buf = new byte[65536];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".swf")) return "application/x-shockwave-flash";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".xml")) return "text/xml";
        return "application/octet-stream";
    }

    private static void send(Socket s, int code, String type, byte[] body) throws Exception {
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 404 ? "Not Found" : "Forbidden") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ASCII"));
        out.write(body);
        out.flush();
        s.close();
    }
}
