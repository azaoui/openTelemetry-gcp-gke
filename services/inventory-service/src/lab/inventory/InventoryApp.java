package lab.inventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * inventory-service : feuille de la chaîne d'appels (frontend -> order -> inventory).
 *
 * AUCUNE dépendance OpenTelemetry dans ce code : toute la télémétrie (spans, métriques, logs)
 * est produite par l'agent Java OpenTelemetry chargé via JAVA_TOOL_OPTIONS.
 *
 * Endpoints :
 *   GET  /inventory/{item}?qty=N          -> vérifie le stock
 *   POST /inventory/{item}/reserve?qty=N  -> réserve le stock
 *   GET  /health
 *
 * Comportements "pédagogiques" pour avoir des choses à observer dans Cloud Trace :
 *   item=slow    -> latence de 1,5 s (span long, visible dans le waterfall)
 *   item=broken  -> HTTP 500 (span en erreur)
 *   ERROR_RATE   -> variable d'env, probabilité d'erreur 500 aléatoire (ex. 0.05)
 */
public class InventoryApp {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s [%3$s] %5$s%6$s%n");
    }

    private static final Logger LOG = Logger.getLogger(InventoryApp.class.getName());
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>(Map.of(
            "laptop", 50, "phone", 120, "keyboard", 300, "mouse", 500, "slow", 1000, "broken", 1000));
    private static final double ERROR_RATE = Double.parseDouble(System.getenv().getOrDefault("ERROR_RATE", "0"));

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/inventory/", InventoryApp::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("inventory-service started on port " + port + " (ERROR_RATE=" + ERROR_RATE + ")");
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            // /inventory/{item} ou /inventory/{item}/reserve
            String[] parts = ex.getRequestURI().getPath().split("/");
            String item = parts.length > 2 ? parts[2] : "";
            boolean reserve = parts.length > 3 && "reserve".equals(parts[3]);
            int qty = Integer.parseInt(query(ex.getRequestURI()).getOrDefault("qty", "1"));
            // Header W3C injecté automatiquement par l'agent côté appelant (order-service)
            String traceparent = String.valueOf(ex.getRequestHeaders().getFirst("traceparent"));

            simulateWork(item);

            if ("broken".equals(item) || ThreadLocalRandom.current().nextDouble() < ERROR_RATE) {
                LOG.severe("Inventory backend failure for item=" + item);
                send(ex, 500, "{\"error\":\"inventory backend failure\",\"item\":\"" + item + "\"}");
                return;
            }
            Integer stock = STOCK.get(item);
            if (stock == null) {
                LOG.warning("Unknown item " + item);
                send(ex, 404, "{\"error\":\"unknown item\",\"item\":\"" + item + "\"}");
                return;
            }
            if (reserve && "POST".equals(ex.getRequestMethod())) {
                if (stock < qty) {
                    send(ex, 409, "{\"error\":\"insufficient stock\",\"item\":\"" + item + "\"}");
                    return;
                }
                STOCK.compute(item, (k, v) -> v - qty);
                LOG.info("Reserved " + qty + " x " + item);
                send(ex, 200, "{\"item\":\"" + item + "\",\"reserved\":" + qty
                        + ",\"traceparentReceived\":\"" + traceparent + "\"}");
            } else {
                LOG.info("Stock check item=" + item + " qty=" + qty + " stock=" + stock);
                send(ex, 200, "{\"item\":\"" + item + "\",\"stock\":" + stock + ",\"available\":" + (stock >= qty)
                        + ",\"traceparentReceived\":\"" + traceparent + "\"}");
            }
        } catch (Exception e) {
            LOG.severe("Unexpected error: " + e);
            send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    private static void simulateWork(String item) {
        long ms = "slow".equals(item) ? 1500 : ThreadLocalRandom.current().nextLong(10, 60);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
