package dev.tunewire.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Small URL helpers for the OAuth callback and the REST calls. */
public final class Http {
    private Http() {}

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** "a=1&b=x%20y" → {a=1, b=x y}. */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    public static String form(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return j.toString();
    }

    /** Request target of an HTTP/1.1 request line: "GET /callback?x=1 HTTP/1.1" → "/callback?x=1"; null if malformed. */
    public static String requestTarget(String requestLine) {
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[0].equals("GET")) return null;
        return parts[1];
    }
}
