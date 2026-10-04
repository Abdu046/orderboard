import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    static final int PORT = 18080;
    static final String DATE = LocalDate.now().plusDays(1).toString();
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    static final Map<String, User> users = new HashMap<>();
    static final Map<Integer, Product> products = new LinkedHashMap<>();
    static final Map<Integer, Integer> requested = new HashMap<>();
    static final List<Notification> notifications = new ArrayList<>();
    static String lastSubmittedAt = null;
    static String status = "DRAFT";

    record User(String username, String password, String role, String name) {}
    record Product(int id, String name, String unit, String supplier) {}
    record Notification(String message, LocalDateTime createdAt, boolean read) {}

    public static void main(String[] args) throws Exception {
        seed();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleStatic);
        server.createContext("/api/login", Main::login);
        server.createContext("/api/products", Main::products);
        server.createContext("/api/update", Main::update);
        server.createContext("/api/submit", Main::submit);
        server.createContext("/api/confirm", Main::confirm);
        server.createContext("/api/state", Main::state);
        server.createContext("/api/notifications", Main::notifications);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("OrderBoard Beta: http://localhost:" + PORT);
        System.out.println("Продавец: seller / 1234");
        System.out.println("Поставщик: supplier / 1234");
    }

    static void seed() {
        users.put("seller", new User("seller", "1234", "SELLER", "Продавец магазина"));
        users.put("supplier", new User("supplier", "1234", "SUPPLIER", "Поставщик"));

        products.put(1, new Product(1, "Молоко 2.5%", "шт", "Поставщик №1"));
        products.put(2, new Product(2, "Кефир", "шт", "Поставщик №1"));
        products.put(3, new Product(3, "Сметана", "шт", "Поставщик №1"));
        products.put(4, new Product(4, "Хлеб", "шт", "Поставщик №2"));
        products.put(5, new Product(5, "Coca-Cola 0.5", "шт", "Поставщик №2"));
        products.put(6, new Product(6, "Вода 1.5л", "бут", "Поставщик №2"));
        products.put(7, new Product(7, "Картофель", "кг", "Поставщик №3"));
        products.put(8, new Product(8, "Помидоры", "кг", "Поставщик №3"));
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        File file = new File("src/main/resources/static" + path);
        if (!file.exists() || file.isDirectory()) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        String contentType = path.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
        send(ex, 200, contentType, java.nio.file.Files.readString(file.toPath()));
    }

    static void login(HttpExchange ex) throws IOException {
        Map<String,String> p = form(ex);
        User u = users.get(p.get("username"));
        boolean ok = u != null && Objects.equals(u.password(), p.get("password"));
        if (!ok) {
            send(ex, 401, "application/json", "{\"ok\":false,\"message\":\"Неверный логин или пароль\"}");
            return;
        }
        String json = String.format(Locale.US,
                "{\"ok\":true,\"username\":\"%s\",\"role\":\"%s\",\"name\":\"%s\"}",
                esc(u.username()), esc(u.role()), esc(u.name()));
        send(ex, 200, "application/json", json);
    }

    static void products(HttpExchange ex) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        synchronized (products) {
            for (Product p : products.values()) {
                if (!first) json.append(',');
                first = false;
                int qty = requested.getOrDefault(p.id(), 0);
                json.append(String.format(Locale.US,
                        "{\"id\":%d,\"name\":\"%s\",\"unit\":\"%s\",\"supplier\":\"%s\",\"qty\":%d}",
                        p.id(), esc(p.name()), esc(p.unit()), esc(p.supplier()), qty));
            }
        }
        json.append(']');
        send(ex, 200, "application/json", json.toString());
    }

    static void update(HttpExchange ex) throws IOException {
        Map<String,String> p = form(ex);
        try {
            int id = Integer.parseInt(p.get("id"));
            int qty = Math.max(0, Integer.parseInt(p.get("qty")));
            if (!products.containsKey(id)) throw new IllegalArgumentException();
            requested.put(id, qty);
            if (status.equals("CONFIRMED")) status = "DRAFT";
            send(ex, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            send(ex, 400, "application/json", "{\"ok\":false,\"message\":\"Неверные данные\"}");
        }
    }

    static void submit(HttpExchange ex) throws IOException {
        int total = requested.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            send(ex, 400, "application/json", "{\"ok\":false,\"message\":\"Сначала добавьте хотя бы один товар\"}");
            return;
        }
        lastSubmittedAt = LocalDateTime.now().format(FMT);
        status = "SUBMITTED";
        synchronized (notifications) {
            notifications.add(new Notification(
                    "🔔 Магазин обновил заявку на товары на " + DATE + ". Можно готовить поставку.",
                    LocalDateTime.now(), false));
        }
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    static void confirm(HttpExchange ex) throws IOException {
        status = "CONFIRMED";
        synchronized (notifications) {
            notifications.add(new Notification(
                    "✅ Поставщик подтвердил заявку. Товары на завтра приняты в работу.",
                    LocalDateTime.now(), false));
        }
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    static void state(HttpExchange ex) throws IOException {
        int total = requested.values().stream().mapToInt(Integer::intValue).sum();
        String json = String.format(Locale.US,
                "{\"date\":\"%s\",\"status\":\"%s\",\"lastSubmittedAt\":%s,\"totalItems\":%d}",
                DATE, status, lastSubmittedAt == null ? "null" : "\"" + esc(lastSubmittedAt) + "\""", total);
        send(ex, 200, "application/json", json);
    }

    static void notifications(HttpExchange ex) throws IOException {
        StringBuilder json = new StringBuilder("[");
        synchronized (notifications) {
            boolean first = true;
            for (int i = notifications.size() - 1; i >= 0; i--) {
                Notification n = notifications.get(i);
                if (!first) json.append(',');
                first = false;
                json.append("{\"message\":\"").append(esc(n.message())).append("\",\"time\":\"")
                        .append(esc(n.createdAt().format(FMT))).append("\"}");
            }
        }
        json.append(']');
        send(ex, 200, "application/json", json.toString());
    }

    static Map<String,String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] a = pair.split("=", 2);
            String key = URLDecoder.decode(a[0], StandardCharsets.UTF_8);
            String value = a.length > 1 ? URLDecoder.decode(a[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
