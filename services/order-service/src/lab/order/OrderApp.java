package lab.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * order-service : maillon central (frontend -> order -> inventory).
 *
 * POST /orders?item=X&qty=N
 *   1. GET  inventory /inventory/{item}          (span CLIENT n°1)
 *   2. POST inventory /inventory/{item}/reserve  (span CLIENT n°2)
 *   3. persistOrder()  -> span INTERNAL créé SANS code, via OTEL_INSTRUMENTATION_METHODS_INCLUDE
 *
 * Toujours aucune dépendance OpenTelemetry : l'agent instrumente com.sun.net.httpserver (spans SERVER)
 * et java.net.http.HttpClient (spans CLIENT + injection du header traceparent).
 */
public class OrderApp {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s [%3$s] %5$s%6$s%n");
    }

    private static final Logger LOG = Logger.getLogger(OrderApp.class.getName());
    private static final String INVENTORY_URL =
            System.getenv().getOrDefault("INVENTORY_URL", "http://localhost:8082");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/orders", OrderApp::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("order-service started on port " + port + ", inventory=" + INVENTORY_URL);
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, "{\"error\":\"use POST\"}");
                return;
            }
            Map<String, String> q = query(ex.getRequestURI());
            String item = q.getOrDefault("item", "laptop");
            int qty = Integer.parseInt(q.getOrDefault("qty", "1"));
            LOG.info("New order request item=" + item + " qty=" + qty);

            // 1) vérification du stock
            HttpResponse<String> check = HTTP.send(HttpRequest.newBuilder(
                            URI.create(INVENTORY_URL + "/inventory/" + item + "?qty=" + qty))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (check.statusCode() != 200) {
                LOG.warning("Inventory check failed status=" + check.statusCode());
                send(ex, 502, "{\"error\":\"inventory check failed\",\"inventoryStatus\":" + check.statusCode()
                        + ",\"inventory\":" + check.body() + "}");
                return;
            }
            if (check.body().contains("\"available\":false")) {
                send(ex, 409, "{\"error\":\"out of stock\",\"inventory\":" + check.body() + "}");
                return;
            }

            // 2) réservation
            HttpResponse<String> reserve = HTTP.send(HttpRequest.newBuilder(
                            URI.create(INVENTORY_URL + "/inventory/" + item + "/reserve?qty=" + qty))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (reserve.statusCode() != 200) {
                send(ex, 502, "{\"error\":\"reservation failed\",\"inventoryStatus\":" + reserve.statusCode() + "}");
                return;
            }

            // 3) "persistance" : méthode métier -> span INTERNAL via config de l'agent
            String orderId = persistOrder(item, qty);
            LOG.info("Order " + orderId + " created");
            send(ex, 201, "{\"orderId\":\"" + orderId + "\",\"item\":\"" + item + "\",\"qty\":" + qty
                    + ",\"traceparentReceived\":\"" + ex.getRequestHeaders().getFirst("traceparent") + "\""
                    + ",\"inventory\":" + reserve.body() + "}");
        } catch (Exception e) {
            LOG.severe("Order failed: " + e);
            send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    /** Simule l'écriture en base. Cible de OTEL_INSTRUMENTATION_METHODS_INCLUDE. */
    static String persistOrder(String item, int qty) throws InterruptedException {
        Thread.sleep(25);
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        if (uri.getRawQuery() == null) return m;
        for (String kv : uri.getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], p.length > 1 ? java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
