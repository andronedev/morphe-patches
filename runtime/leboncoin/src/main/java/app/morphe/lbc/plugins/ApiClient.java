package app.morphe.lbc.plugins;

import app.morphe.lbc.net.HttpBridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejoue des appels à l'API leboncoin avec la session de l'app.
 *
 * <p><b>Limite connue, importante :</b> l'app embarque DataDome
 * (`co.datadome.sdk.DataDomeInterceptor`). Une requête émise ici, hors de la pile OkHttp de l'app,
 * n'est pas signée par cet intercepteur et sera vraisemblablement rejetée (« Blocked request by
 * DataDome »). C'est le mode « v1 » : utile pour mettre au point, insuffisant en production.
 *
 * <p>La suite prévue est de passer par le client de l'app elle-même (capture de l'instance Retrofit
 * et des `*ApiService`), c'est-à-dire d'emprunter son chemin de requête normal. Rien ici ne cherche
 * à falsifier ou contourner DataDome : si l'appel ne passe pas par le client de l'app, il ne passe
 * pas, point.
 */
final class ApiClient {

    private final Map<String, String> headers = new ConcurrentHashMap<>();

    /** Mémorise les en-têtes de session vus passer sur les requêtes de l'app. */
    void captureFrom(String urlFragment) {
        HttpBridge.onRequest(urlFragment, exchange -> {
            for (Map.Entry<String, String> header : exchange.requestHeaders().entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (name.equals("authorization") || name.equals("user-agent")
                        || name.startsWith("api-key") || name.startsWith("x-")) {
                    headers.put(header.getKey(), header.getValue());
                }
            }
        });
    }

    boolean hasSession() {
        return !headers.isEmpty();
    }

    Map<String, String> capturedHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    Response send(String method, String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Content-Type", "application/json");

            if (body != null && !body.isEmpty()) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, read(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toString("UTF-8");
    }

    static final class Response {

        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        /** Un blocage anti-bot ne se présente pas comme une erreur métier : il faut le distinguer. */
        boolean looksBlocked() {
            return status == 403 || body.contains("DataDome") || body.contains("datadome");
        }
    }
}
