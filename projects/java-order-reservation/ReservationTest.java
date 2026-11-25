import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

public class ReservationTest {
    private static HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders/" + path))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
    }

    private static void expect(int expected, int actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Exception {
        var inventory = ReservationDemo.startInventory(0);
        var orders = ReservationDemo.startOrders(0, inventory.getAddress().getPort());
        int port = orders.getAddress().getPort();
        try (var client = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                expect(201, client.send(request(port, "initial/book/2"), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            expect(409, client.send(request(port, "initial/book/1"), HttpResponse.BodyHandlers.discarding()).statusCode());
            var attempts = IntStream.range(0, 10)
                    .mapToObj(i -> client.sendAsync(request(port, "order-" + i + "/book/1"),
                            HttpResponse.BodyHandlers.discarding()))
                    .toList();
            expect(3, (int) attempts.stream().map(CompletableFuture::join)
                    .filter(response -> response.statusCode() == 201).count());
            expect(7, (int) attempts.stream().map(CompletableFuture::join)
                    .filter(response -> response.statusCode() == 409).count());
            expect(400, client.send(request(port, "new/book/0"), HttpResponse.BodyHandlers.discarding()).statusCode());
            inventory.stop(0);
            expect(503, client.send(request(port, "new/book/1"), HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {
            inventory.stop(0);
            orders.stop(0);
        }
        System.out.println("Reservation integration tests passed");
    }
}
