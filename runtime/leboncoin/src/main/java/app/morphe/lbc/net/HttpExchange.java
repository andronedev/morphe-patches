package app.morphe.lbc.net;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Un échange HTTP observé, tel que transmis par le shim injecté dans l'APK. */
public final class HttpExchange {

    public final String url;
    public final String method;
    public final String requestBody;
    public final String responseBody;

    private final Map<String, String> requestHeaders;

    public HttpExchange(String url, String method, Map<String, String> requestHeaders,
                        String requestBody, String responseBody) {
        this.url = url == null ? "" : url;
        this.method = method == null ? "GET" : method;
        this.requestHeaders = requestHeaders == null ? Collections.emptyMap() : requestHeaders;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
    }

    public Map<String, String> requestHeaders() {
        return Collections.unmodifiableMap(requestHeaders);
    }

    public String header(String name) {
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean urlContains(String fragment) {
        return url.contains(fragment);
    }

    /** Chemin sans query string, pratique pour le routage des plugins. */
    public String path() {
        int scheme = url.indexOf("://");
        int start = scheme < 0 ? 0 : url.indexOf('/', scheme + 3);
        if (start < 0) {
            return "/";
        }
        int query = url.indexOf('?', start);
        return query < 0 ? url.substring(start) : url.substring(start, query);
    }

    static Map<String, String> parseHeaders(String headersJson) {
        Map<String, String> out = new HashMap<>();
        if (headersJson == null || headersJson.isEmpty()) {
            return out;
        }
        try {
            JSONObject object = new JSONObject(headersJson);
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                out.put(key, object.optString(key, ""));
            }
        } catch (Throwable ignored) {
            // En-têtes illisibles : on continue sans, ce n'est jamais bloquant.
        }
        return out;
    }
}
