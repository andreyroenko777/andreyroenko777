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
import java.util.concurrent.Executors;

public class ReservationDemo {
    record Reservation(String sku, int quantity, int status) {}

    static final class Inventory {
        private final Map<String, Integer> stock = new HashMap<>(Map.of("book", 5));
        private final Map<String, Reservation> requests = new HashMap<>();

        synchronized int reserve(String id, String sku, int quantity) {
            Reservation previous = requests.get(id);
            if (previous != null) {
                if (previous.sku().equals(sku) && previous.quantity() == quantity) {
                    return previous.status();
                }
                return 409;
            }

            Integer available = stock.get(sku);
            int status;
            if (available == null) {
                status = 404;
            } else if (available < quantity) {
                status = 409;
            } else {
                stock.put(sku, available - quantity);
                status = 201;
            }
            requests.put(id, new Reservation(sku, quantity, status));
            return status;
        }
    }

    static void reply(HttpExchange exchange, int status) throws IOException {
        String result = switch (status) {
            case 201 -> "reserved";
            case 404 -> "sku_not_found";
            case 405 -> "method_not_allowed";
            case 409 -> "reservation_conflict";
            case 503 -> "inventory_unavailable_retry_same_id";
            default -> "invalid_request";
        };
        byte[] body = ("{\"status\":\"" + result + "\"}\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 405) {
            exchange.getResponseHeaders().set("Allow", "PUT");
        }
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    static int validate(HttpExchange exchange, String prefix) {
        if (!"PUT".equals(exchange.getRequestMethod())) {
            return 405;
        }
        String path = exchange.getRequestURI().getRawPath();
        return path.matches(prefix + "/[a-zA-Z0-9-]{1,64}/[a-zA-Z0-9-]{1,32}/[1-9][0-9]{0,5}") ? 0 : 400;
    }

    static HttpServer startInventory(int port) throws IOException {
        var inventory = new Inventory();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/reservations", exchange -> {
            int invalid = validate(exchange, "/reservations");
            if (invalid != 0) {
                reply(exchange, invalid);
                return;
            }
            String[] parts = exchange.getRequestURI().getRawPath().split("/");
            reply(exchange, inventory.reserve(parts[2], parts[3], Integer.parseInt(parts[4])));
        });
        server.start();
        return server;
    }

    static HttpServer startOrders(int port, int inventoryPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        server.createContext("/orders", exchange -> {
            int status = validate(exchange, "/orders");
            if (status == 0) {
                String path = exchange.getRequestURI().getRawPath().substring("/orders".length());
                URI uri = URI.create("http://127.0.0.1:" + inventoryPort + "/reservations" + path);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build();
                try {
                    status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status = 503;
                } catch (IOException exception) {
                    status = 503;
                }
            }
            reply(exchange, status);
        });
        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || (!args[0].equals("inventory") && !args[0].equals("orders"))) {
            throw new IllegalArgumentException("Run with inventory or orders");
        }
        if (args[0].equals("inventory")) {
            startInventory(8081);
        } else {
            startOrders(8080, 8081);
        }
    }
}
