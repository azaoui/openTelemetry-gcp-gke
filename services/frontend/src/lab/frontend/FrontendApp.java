package lab.frontend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * frontend : point d'entrée (span racine de chaque trace).
 *
 * GET /checkout?item=laptop&qty=1  -> POST order-service /orders
 * GET /                             -> aide
 *
 * Items : laptop, phone, keyboard, mouse (OK) | slow (latence) | broken (erreur 500) | xyz (404)
 */
public class FrontendApp {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s [%3$s] %5$s%6$s%n");
    }

    private static final Logger LOG = Logger.getLogger(FrontendApp.class.getName());
    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_URL", "http://localhost:8081");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/checkout", FrontendApp::checkout);
        server.createContext("/", ex -> send(ex, 200,
                "{\"usage\":\"GET /checkout?item=laptop&qty=1\",\"items\":[\"laptop\",\"phone\",\"keyboard\",\"mouse\",\"slow\",\"broken\"]}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("frontend started on port " + port + ", order-service=" + ORDER_URL);
    }

    private static void checkout(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = query(ex.getRequestURI());
            String item = q.getOrDefault("item", "laptop");
            String qty = q.getOrDefault("qty", "1");
            LOG.info("Checkout item=" + item + " qty=" + qty);

            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(ORDER_URL + "/orders?item="
                                    + URLEncoder.encode(item, StandardCharsets.UTF_8) + "&qty=" + qty))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            int status = resp.statusCode() >= 500 ? 502 : resp.statusCode();
            if (status >= 400) LOG.warning("Checkout failed, order-service status=" + resp.statusCode());
            send(ex, status, "{\"checkout\":\"" + (status < 400 ? "OK" : "KO") + "\",\"order\":" + resp.body() + "}");
        } catch (Exception e) {
            LOG.severe("Checkout error: " + e);
            send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
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
