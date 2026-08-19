package app.morphe.lbc.net;

import android.content.Context;

import app.morphe.lbc.Lbc;
import app.morphe.lbc.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Point de passage de tout le trafic HTTP de l'app.
 *
 * <p><b>Pourquoi ici et pas dans l'UI ?</b> L'app est en Compose et entièrement obfusquée : hooker
 * l'affichage casse à chaque release. Le JSON de l'API, lui, bouge lentement. No-ads, filtres et
 * auto-repost passent donc tous par cette couche.
 *
 * <h3>Contrat avec le shim injecté (ne pas renommer)</h3>
 * Le patch Morphe génère dans l'APK une classe qui implémente l'interface `Interceptor` d'OkHttp
 * (dont le nom est obfusqué, donc inconnu du runtime) et qui appelle par réflexion, en ne passant
 * que des types du JDK :
 *
 * <pre>
 * boolean app.morphe.lbc.net.HttpBridge.shouldBlock(String url)
 * String  app.morphe.lbc.net.HttpBridge.onExchange(String url, String method,
 *                                                  String requestHeadersJson,
 *                                                  String requestBody,
 *                                                  String responseBody)
 * </pre>
 *
 * <p>{@code onExchange} renvoie le corps de réponse de remplacement, ou {@code null} pour laisser
 * passer tel quel. Aucune classe OkHttp ne traverse cette frontière : c'est ce qui permet au
 * runtime de survivre à un renommage complet de l'app.
 */
public final class HttpBridge {

    private static final Logger LOG = new Logger("Http");

    /** Domaines des régies pub / trackers, court-circuités avant même l'envoi. */
    private static final List<String> BLOCKED_HOSTS = new CopyOnWriteArrayList<>(Arrays.asList(
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "securepubads.g.doubleclick.net",
            "static.criteo.net",
            "bidder.criteo.com",
            "ms.applvn.com",
            "rt.applvn.com",
            "prg.smartadserver.com",
            "diff.smartadserver.com",
            "a.teads.tv",
            "sdk.teads.tv",
            "widgets.outbrain.com"
    ));

    private static final List<Subscription> RESPONSE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Subscription> REQUEST_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean initialised;

    private HttpBridge() {
    }

    public static void init(Context context) {
        initialised = true;
        LOG.i("pont HTTP prêt (" + BLOCKED_HOSTS.size() + " hôtes bloqués)");
    }

    // ------------------------------------------------------------------ API plugins

    /** Observe (et éventuellement réécrit) les réponses dont l'URL contient {@code urlFragment}. */
    public static void onResponse(String urlFragment, ResponseListener listener) {
        RESPONSE_LISTENERS.add(new Subscription(urlFragment, listener));
    }

    /** Observe les requêtes sortantes — sert notamment à capturer les en-têtes d'authentification. */
    public static void onRequest(String urlFragment, RequestListener listener) {
        REQUEST_LISTENERS.add(new Subscription(urlFragment, listener));
    }

    public static void blockHost(String host) {
        String normalised = host.toLowerCase(Locale.ROOT);
        if (!BLOCKED_HOSTS.contains(normalised)) {
            BLOCKED_HOSTS.add(normalised);
        }
    }

    public static List<String> blockedHosts() {
        return new ArrayList<>(BLOCKED_HOSTS);
    }

    // -------------------------------------------------------------- appelé par le shim

    /** @return true si la requête doit être court-circuitée (réponse vide, sans appel réseau). */
    public static boolean shouldBlock(String url) {
        if (!initialised || url == null) {
            return false;
        }
        try {
            String lower = url.toLowerCase(Locale.ROOT);
            for (String host : BLOCKED_HOSTS) {
                if (lower.contains(host)) {
                    if (Lbc.isDebug()) {
                        LOG.d("bloqué: " + url);
                    }
                    return true;
                }
            }
        } catch (Throwable error) {
            LOG.e("shouldBlock a échoué, requête laissée passer", error);
        }
        return false;
    }

    /**
     * @return corps de réponse de remplacement, ou {@code null} pour ne rien changer.
     */
    public static String onExchange(String url, String method, String requestHeadersJson,
                                    String requestBody, String responseBody) {
        if (!initialised) {
            return null;
        }
        HttpExchange exchange;
        try {
            Map<String, String> headers = HttpExchange.parseHeaders(requestHeadersJson);
            exchange = new HttpExchange(url, method, headers, requestBody, responseBody);
        } catch (Throwable error) {
            LOG.e("échange illisible, ignoré", error);
            return null;
        }

        for (Subscription subscription : REQUEST_LISTENERS) {
            if (subscription.matches(exchange.url)) {
                try {
                    ((RequestListener) subscription.listener).onRequest(exchange);
                } catch (Throwable error) {
                    LOG.e("listener de requête en échec (" + subscription.urlFragment + ")", error);
                }
            }
        }

        String body = responseBody;
        for (Subscription subscription : RESPONSE_LISTENERS) {
            if (!subscription.matches(exchange.url)) {
                continue;
            }
            try {
                HttpExchange current = new HttpExchange(
                        exchange.url, exchange.method, exchange.requestHeaders(), exchange.requestBody, body);
                String rewritten = ((ResponseListener) subscription.listener).onResponse(current);
                if (rewritten != null) {
                    body = rewritten;
                }
            } catch (Throwable error) {
                // Un plugin qui plante ne doit jamais casser l'affichage : on garde le corps précédent.
                LOG.e("listener de réponse en échec (" + subscription.urlFragment + ")", error);
            }
        }
        //noinspection StringEquality — comparaison d'identité voulue : rien de réécrit.
        return body == responseBody ? null : body;
    }

    // ------------------------------------------------------------------------ types

    public interface ResponseListener {
        /** @return le corps réécrit, ou {@code null} pour laisser passer. */
        String onResponse(HttpExchange exchange);
    }

    public interface RequestListener {
        void onRequest(HttpExchange exchange);
    }

    private static final class Subscription {

        final String urlFragment;
        final Object listener;

        Subscription(String urlFragment, Object listener) {
            this.urlFragment = urlFragment == null ? "" : urlFragment;
            this.listener = listener;
        }

        boolean matches(String url) {
            return urlFragment.isEmpty() || url.contains(urlFragment);
        }
    }
}
