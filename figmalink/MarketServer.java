import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class MarketServer {
    private static final int MAX_BODY_BYTES = 32_768;
    private static final Path PAGE = Path.of("Untitled-1.html");
    private static final Map<String, StaticFile> STATIC_FILES = Map.of(
            "/manifest.webmanifest", new StaticFile(Path.of("manifest.webmanifest"), "application/manifest+json; charset=utf-8"),
            "/service-worker.js", new StaticFile(Path.of("service-worker.js"), "text/javascript; charset=utf-8"),
            "/icon.svg", new StaticFile(Path.of("icon.svg"), "image/svg+xml; charset=utf-8"));
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> CATEGORIES = Set.of("Rose", "Tulip", "Sunflower", "Lily", "Carnation", "Daisy", "Orchid", "Mixed Bouquet");
    private static final Set<String> FLOWER_COLORS = Set.of("Red", "Pink", "White", "Yellow", "Purple", "Blue", "Orange", "Mixed");
    private static final Set<String> EMOJIS = Set.of("💐", "🌹", "🌻", "🌷", "🌸", "🪷", "🌼");
    private static final Set<String> ORDER_STATUSES = Set.of("Pending", "Preparing", "Out for delivery", "Delivered");
    private static final Set<String> DELIVERY_METHODS = Set.of("Delivery", "Pick Up");
    private static final Set<String> PAYMENT_METHODS = Set.of("Cash on Delivery", "Bank Transfer", "Gcash");
    private static final Pattern JSON_STRING = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final String GOOGLE_CLIENT_ID = System.getenv().getOrDefault("GOOGLE_CLIENT_ID", "").strip();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    private static final List<Product> PRODUCTS = new ArrayList<>();
    private static final List<Order> ORDERS = new ArrayList<>();
    private static final Map<String, UserSession> SESSIONS = new HashMap<>();
    private static final Map<String, Profile> SELLER_PROFILES = new HashMap<>();
    private static int nextProduct = 1007;
    private static int nextOrder = 1001;

    private MarketServer() {}

    public static void main(String[] args) throws IOException {
        String configuredPort = args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "8080");
        int port = Integer.parseInt(configuredPort);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        seedProducts();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", MarketServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("MadeForU is listening on port " + port);
    }

    private static void seedProducts() {
        PRODUCTS.add(new Product("P-1001", "Fresh Pink Rose Bouquet", "Rose", "A hand-tied bouquet of fresh pink roses, prepared by a local florist.", "Blooming Crafts", "🌹", "Pink", "#f1d8dc", 40000, 5, "Harvested today", "09171234567", "Panabo City", "Blooming Crafts florist", "flowers@example.com", ""));
        PRODUCTS.add(new Product("P-1002", "Hand-Tied White Lily Bouquet", "Lily", "Fresh white lilies arranged and wrapped by hand to order.", "Blooming Crafts", "🌸", "White", "#eee9df", 45000, 4, "Fresh cut · 1 day", "09171234567", "Panabo City", "Blooming Crafts florist", "flowers@example.com", ""));
        PRODUCTS.add(new Product("P-1003", "Red Garden Roses", "Rose", "A hand-arranged bunch of fresh red garden roses.", "Petal & Stem", "🌹", "Red", "#f0d7d2", 50000, 6, "Harvested today", "09179876543", "Tagum City", "Petal & Stem florist", "petal@example.com", ""));
        PRODUCTS.add(new Product("P-1004", "Yellow Tulip Bunch", "Tulip", "Bright, freshly cut yellow tulips gathered into a hand-tied bunch.", "Mira's Flowers", "🌷", "Yellow", "#f1e8c9", 35000, 8, "Fresh cut · 1 day", "09175551234", "Davao City", "Mira's florist", "mira@example.com", ""));
        PRODUCTS.add(new Product("P-1005", "Sunflower Hand-Tied Bouquet", "Sunflower", "Locally grown fresh sunflowers arranged by hand.", "Golden Hour Florals", "🌻", "Yellow", "#f0e2b9", 30000, 12, "Harvested today", "09176662345", "Panabo City", "Golden Hour florist", "goldenhour@example.com", ""));
        PRODUCTS.add(new Product("P-1006", "Purple Daisy Posy", "Daisy", "A small hand-tied posy of fresh purple daisies.", "Blooming Crafts", "🌼", "Purple", "#e9e3ed", 25000, 7, "Fresh cut · 1 day", "09171234567", "Panabo City", "Blooming Crafts florist", "flowers@example.com", ""));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/health".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
            } else if ("/".equals(path) || "/Untitled-1.html".equals(path)) {
                servePage(exchange, method);
            } else if (STATIC_FILES.containsKey(path)) {
                serveStaticFile(exchange, method, STATIC_FILES.get(path));
            } else if ("/api/auth/config".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"googleClientId\":" + json(GOOGLE_CLIENT_ID) + "}");
            } else if ("/api/auth/google".equals(path) && "POST".equals(method)) {
                signInWithGoogle(exchange);
            } else if ("/api/auth/me".equals(path) && "GET".equals(method)) {
                UserSession user = getSession(exchange);
                sendJson(exchange, 200, "{\"user\":" + (user == null ? "null" : userJson(user)) + "}");
            } else if ("/api/auth/logout".equals(path) && "POST".equals(method)) {
                signOut(exchange);
            } else if ("/api/state".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, stateJson(getSession(exchange)));
            } else if ("/api/orders".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "customer");
                if (user != null) createOrder(exchange, user);
            } else if ("/api/seller/products".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "seller");
                if (user != null) createProduct(exchange, user);
            } else if ("/api/seller/products/delete".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "seller");
                if (user != null) deleteProduct(exchange, user);
            } else if ("/api/seller/orders/status".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "seller");
                if (user != null) updateOrderStatus(exchange, user);
            } else if ("/api/seller/profile".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "seller");
                if (user != null) updateProfile(exchange, user);
            } else if (path.startsWith("/api/")) {
                String allowed = allowedMethods(path);
                if (!allowed.isEmpty()) exchange.getResponseHeaders().set("Allow", allowed);
                sendError(exchange, 405, "Method not allowed.");
            } else {
                sendError(exchange, 404, "Not found.");
            }
        } finally {
            exchange.close();
        }
    }

    private static void servePage(HttpExchange exchange, String method) throws IOException {
        if (!"GET".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        if (!Files.isRegularFile(PAGE)) {
            System.err.println("Page file not found: " + PAGE.toAbsolutePath());
            sendError(exchange, 500, "The page could not be loaded.");
            return;
        }
        byte[] content = Files.readAllBytes(PAGE);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void serveStaticFile(HttpExchange exchange, String method, StaticFile file) throws IOException {
        if (!"GET".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        if (!Files.isRegularFile(file.path)) {
            System.err.println("Static file not found: " + file.path.toAbsolutePath());
            sendError(exchange, 500, "An app resource could not be loaded.");
            return;
        }
        byte[] content = Files.readAllBytes(file.path);
        exchange.getResponseHeaders().set("Content-Type", file.contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "/service-worker.js".equals(exchange.getRequestURI().getPath())
                ? "no-cache" : "public, max-age=3600");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void signInWithGoogle(HttpExchange exchange) throws IOException {
        if (GOOGLE_CLIENT_ID.isBlank()) {
            sendError(exchange, 503, "Google sign-in is not configured. Set GOOGLE_CLIENT_ID in the deployment environment.");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String credential = fields.getOrDefault("credential", "");
        String role = fields.getOrDefault("role", "");
        if (credential.isBlank() || credential.length() > 8192
                || !Set.of("customer", "seller").contains(role)) {
            sendError(exchange, 400, "A Google sign-in token and account type are required.");
            return;
        }
        try {
            URI tokenInfoUri = URI.create("https://oauth2.googleapis.com/tokeninfo?id_token="
                    + URLEncoder.encode(credential, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(tokenInfoUri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                System.err.println("Google token verification request failed.");
                sendError(exchange, 503, "Google sign-in is temporarily unavailable. Please try again.");
                return;
            }
            String body = response.body();
            String subject = jsonStringField(body, "sub");
            String email = jsonStringField(body, "email");
            String name = jsonStringField(body, "name");
            String audience = jsonStringField(body, "aud");
            if (response.statusCode() != 200 || subject.isBlank() || !EMAIL.matcher(email).matches()
                    || !GOOGLE_CLIENT_ID.equals(audience)
                    || !(body.matches("(?s).*\"email_verified\"\\s*:\\s*true(?:\\s*[,}]).*")
                    || body.matches("(?s).*\"email_verified\"\\s*:\\s*\"true\".*"))) {
                sendError(exchange, 401, "Google could not verify this account. Please try signing in again.");
                return;
            }
            if (name.isBlank()) name = email.substring(0, email.indexOf('@'));
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            UserSession user = new UserSession(sessionId, subject, name, email, jsonStringField(body, "picture"),
                    role, Instant.now().plus(Duration.ofDays(7)));
            synchronized (LOCK) {
                SESSIONS.values().removeIf(session -> session.expiresAt.isBefore(Instant.now()));
                SESSIONS.put(sessionId, user);
            }
            setSessionCookie(exchange, sessionId, 7 * 24 * 60 * 60);
            sendJson(exchange, 200, "{\"user\":" + userJson(user) + "}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "Google sign-in was interrupted. Please try again.");
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "The Google sign-in response was invalid.");
        }
    }

    private static String jsonStringField(String json, String field) {
        Pattern pattern = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(field)));
        var matcher = pattern.matcher(json);
        if (!matcher.find()) return "";
        String raw = matcher.group(1);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (character != '\\' || i + 1 >= raw.length()) {
                value.append(character);
                continue;
            }
            char escaped = raw.charAt(++i);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 4 >= raw.length()) return "";
                    try {
                        value.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException exception) {
                        return "";
                    }
                }
                default -> value.append(escaped);
            }
        }
        return value.toString();
    }

    private static UserSession getSession(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        String sessionId = "";
        for (String cookie : cookieHeader.split(";")) {
            String[] pair = cookie.strip().split("=", 2);
            if (pair.length == 2 && "madeforu_session".equals(pair[0])) {
                sessionId = pair[1];
                break;
            }
        }
        if (sessionId.isBlank()) return null;
        synchronized (LOCK) {
            UserSession user = SESSIONS.get(sessionId);
            if (user != null && user.expiresAt.isAfter(Instant.now())) return user;
            SESSIONS.remove(sessionId);
            return null;
        }
    }

    private static UserSession requireSession(HttpExchange exchange, String role) throws IOException {
        UserSession user = getSession(exchange);
        if (user == null) {
            sendError(exchange, 401, "Please sign in with Google to continue.");
            return null;
        }
        if (!role.equals(user.role)) {
            sendError(exchange, 403, "Sign in with a " + role + " account to continue.");
            return null;
        }
        return user;
    }

    private static void setSessionCookie(HttpExchange exchange, String sessionId, int maxAge) {
        String secure = "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))
                ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie",
                "madeforu_session=" + sessionId + "; Path=/; Max-Age=" + maxAge
                        + "; HttpOnly; SameSite=Lax" + secure);
    }

    private static void signOut(HttpExchange exchange) throws IOException {
        UserSession user = getSession(exchange);
        if (user != null) {
            synchronized (LOCK) {
                SESSIONS.remove(user.sessionId);
            }
        }
        setSessionCookie(exchange, "", 0);
        sendJson(exchange, 200, "{\"message\":\"Signed out.\"}");
    }

    private static String userJson(UserSession user) {
        return "{\"name\":" + json(user.name) + ",\"email\":" + json(user.email)
                + ",\"picture\":" + json(user.picture) + ",\"role\":" + json(user.role) + "}";
    }

    private static String allowedMethods(String path) {
        if ("/api/state".equals(path)) return "GET";
        if (Set.of("/api/orders", "/api/seller/products", "/api/seller/products/delete",
                "/api/seller/orders/status", "/api/seller/profile").contains(path)) return "POST";
        return "";
    }

    private static void createOrder(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String customer = user.name;
        String email = user.email;
        String address = field(fields, "address");
        String deliveryMethod = fields.getOrDefault("delivery", "Delivery");
        String paymentMethod = fields.getOrDefault("payment", "Cash on Delivery");
        if (customer.isBlank() || customer.length() > 100 || !EMAIL.matcher(email).matches()
                || email.length() > 254 || address.isBlank() || address.length() > 200
                || !DELIVERY_METHODS.contains(deliveryMethod) || !PAYMENT_METHODS.contains(paymentMethod)) {
            sendError(exchange, 400, "Please enter a name, valid email, and delivery address.");
            return;
        }

        Map<String, Integer> requestedItems = parseItems(fields.getOrDefault("items", ""));
        if (requestedItems == null || requestedItems.isEmpty()) {
            sendError(exchange, 400, "Your basket is empty or invalid.");
            return;
        }

        synchronized (LOCK) {
            List<OrderLine> lines = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
                Product product = findProduct(entry.getKey());
                if (product == null || entry.getValue() < 1 || entry.getValue() > 99
                        || product.stock < entry.getValue()) {
                    sendError(exchange, 409, "A product in your basket is no longer available in that quantity.");
                    return;
                }
                lines.add(new OrderLine(product.id, product.name, product.emoji, entry.getValue(),
                        product.priceCents, product.ownerSubject));
                total += (long) product.priceCents * entry.getValue();
            }
            for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
                findProduct(entry.getKey()).stock -= entry.getValue();
            }
            long subtotal = total;
            long protection = 5_000;
            long deliveryFee = "Delivery".equals(deliveryMethod) ? 10_000 : 0;
            total += protection + deliveryFee;
            String orderId = "MF-" + nextOrder++;
            String createdAt = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"));
            ORDERS.add(new Order(orderId, customer, email, address, createdAt, "Pending", lines, subtotal,
                    protection, deliveryFee, deliveryMethod, paymentMethod, total));
            sendJson(exchange, 201, "{\"orderId\":" + json(orderId) + ",\"message\":\"Order placed.\"}");
        }
    }

    private static Map<String, Integer> parseItems(String items) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String item : items.split(",")) {
            if (item.isBlank()) continue;
            String[] parts = item.split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9-]{1,30}")) return null;
            try {
                int quantity = Integer.parseInt(parts[1]);
                if (quantity < 1 || quantity > 99 || result.put(parts[0], quantity) != null) return null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return result;
    }

    private static void createProduct(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String name = field(fields, "name");
        String flowerType = field(fields, "flowerType");
        String description = field(fields, "description");
        String emoji = field(fields, "emoji");
        String color = field(fields, "color");
        String flowerColor = field(fields, "flowerColor");
        String freshness = field(fields, "freshness");
        String sellerPhone = field(fields, "sellerPhone");
        String sellerLocation = field(fields, "sellerLocation");
        String shopName = field(fields, "shopName");
        int stock;
        int priceCents;
        try {
            BigDecimal price = new BigDecimal(field(fields, "price")).setScale(2, RoundingMode.HALF_UP);
            priceCents = price.movePointRight(2).intValueExact();
            stock = Integer.parseInt(field(fields, "stock"));
        } catch (NumberFormatException | ArithmeticException exception) {
            sendError(exchange, 400, "Enter a valid product price and stock quantity.");
            return;
        }
        if (name.isBlank() || name.length() > 80 || !CATEGORIES.contains(flowerType)
                || description.isBlank() || description.length() > 500 || !EMOJIS.contains(emoji)
                || !FLOWER_COLORS.contains(flowerColor) || freshness.isBlank() || freshness.length() > 80
                || !freshness.toLowerCase().contains("fresh") && !freshness.toLowerCase().contains("harvest")
                || sellerPhone.isBlank() || sellerPhone.length() > 30 || sellerLocation.isBlank() || sellerLocation.length() > 100
                || shopName.isBlank() || shopName.length() > 80 || !"true".equals(fields.get("handmade"))
                || !color.matches("#[0-9a-fA-F]{6}") || priceCents < 1 || priceCents > 9_999_999
                || stock < 1 || stock > 9999) {
            sendError(exchange, 400, "Please check the product details and try again.");
            return;
        }
        synchronized (LOCK) {
            String id = "P-" + nextProduct++;
            PRODUCTS.add(new Product(id, name, flowerType, description, shopName, emoji, flowerColor,
                    color, priceCents, stock, freshness, sellerPhone, sellerLocation, user.name, user.email, user.subject));
            SELLER_PROFILES.put(user.subject, new Profile(shopName, user.name, user.email));
            sendJson(exchange, 201, "{\"id\":" + json(id) + ",\"message\":\"Product added.\"}");
        }
    }

    private static void deleteProduct(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        synchronized (LOCK) {
            Product product = findProduct(field(fields, "id"));
            if (product == null || !user.subject.equals(product.ownerSubject)) {
                sendError(exchange, 404, "Product not found.");
                return;
            }
            PRODUCTS.remove(product);
            sendJson(exchange, 200, "{\"message\":\"Product removed.\"}");
        }
    }

    private static void updateOrderStatus(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String id = field(fields, "id");
        String status = field(fields, "status");
        if (!ORDER_STATUSES.contains(status)) {
            sendError(exchange, 400, "That order status is not supported.");
            return;
        }
        synchronized (LOCK) {
            for (Order order : ORDERS) {
                boolean sellerOrder = order.lines.stream().anyMatch(line -> user.subject.equals(line.sellerSubject));
                if (order.id.equals(id) && sellerOrder) {
                    order.status = status;
                    sendJson(exchange, 200, "{\"message\":\"Order updated.\"}");
                    return;
                }
            }
        }
        sendError(exchange, 404, "Order not found.");
    }

    private static void updateProfile(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String shopName = field(fields, "shopName");
        String owner = field(fields, "owner");
        String email = field(fields, "email");
        if (shopName.isBlank() || shopName.length() > 80 || owner.isBlank() || owner.length() > 80
                || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            sendError(exchange, 400, "Please check the shop name, owner, and contact email.");
            return;
        }
        synchronized (LOCK) {
            Profile previousProfile = SELLER_PROFILES.get(user.subject);
            String previousShopName = previousProfile == null
                    ? user.name + "'s Flowers" : previousProfile.shopName;
            SELLER_PROFILES.put(user.subject, new Profile(shopName, owner, email));
            for (Product product : PRODUCTS) {
                if (user.subject.equals(product.ownerSubject) && product.maker.equals(previousShopName)) {
                    product.maker = shopName;
                    product.sellerName = owner;
                    product.sellerEmail = email;
                }
            }
            sendJson(exchange, 200, "{\"message\":\"Profile saved.\"}");
        }
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            sendError(exchange, 415, "Expected URL-encoded form data.");
            return null;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            sendError(exchange, 413, "The submitted data is too large.");
            return null;
        }
        try {
            Map<String, String> fields = new HashMap<>();
            for (String part : new String(body, StandardCharsets.UTF_8).split("&")) {
                if (part.isEmpty()) continue;
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                fields.put(key, value);
            }
            return fields;
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "The submitted data could not be read.");
            return null;
        }
    }

    private static String field(Map<String, String> fields, String name) {
        return fields.getOrDefault(name, "").strip();
    }

    private static Product findProduct(String id) {
        for (Product product : PRODUCTS) {
            if (product.id.equals(id)) return product;
        }
        return null;
    }

    private static String stateJson(UserSession user) {
        synchronized (LOCK) {
            StringBuilder result = new StringBuilder("{\"products\":[");
            for (int i = 0; i < PRODUCTS.size(); i++) {
                if (i > 0) result.append(',');
                result.append(productJson(PRODUCTS.get(i), user));
            }
            result.append("],\"orders\":[");
            boolean firstOrder = true;
            for (Order order : ORDERS) {
                boolean visible = user != null && ("customer".equals(user.role)
                        ? user.email.equalsIgnoreCase(order.email)
                        : order.lines.stream().anyMatch(line -> user.subject.equals(line.sellerSubject)));
                if (visible) {
                    if (!firstOrder) result.append(',');
                    result.append(orderJson(order));
                    firstOrder = false;
                }
            }
            result.append("],\"profile\":");
            Profile profile = user != null && "seller".equals(user.role)
                    ? SELLER_PROFILES.get(user.subject) : null;
            if (profile == null) {
                result.append("null}");
            } else {
                result.append("{\"shopName\":").append(json(profile.shopName))
                        .append(",\"owner\":").append(json(profile.owner))
                        .append(",\"email\":").append(json(profile.email)).append("}}");
            }
            return result.toString();
        }
    }

    private static String productJson(Product product, UserSession user) {
        return "{\"id\":" + json(product.id)
                + ",\"name\":" + json(product.name)
                + ",\"category\":" + json(product.category)
                + ",\"flowerType\":" + json(product.category)
                + ",\"description\":" + json(product.description)
                + ",\"maker\":" + json(product.maker)
                + ",\"sellerName\":" + json(product.sellerName)
                + ",\"sellerEmail\":" + json(product.sellerEmail)
                + ",\"sellerPhone\":" + json(product.sellerPhone)
                + ",\"sellerLocation\":" + json(product.sellerLocation)
                + ",\"ownedByCurrentSeller\":" + (user != null && user.subject.equals(product.ownerSubject))
                + ",\"emoji\":" + json(product.emoji)
                + ",\"flowerColor\":" + json(product.flowerColor)
                + ",\"color\":" + json(product.color)
                + ",\"freshness\":" + json(product.freshness)
                + ",\"handmade\":true"
                + ",\"priceCents\":" + product.priceCents
                + ",\"stock\":" + product.stock + "}";
    }

    private static String orderJson(Order order) {
        StringBuilder result = new StringBuilder("{\"id\":").append(json(order.id))
                .append(",\"customer\":").append(json(order.customer))
                .append(",\"email\":").append(json(order.email))
                .append(",\"address\":").append(json(order.address))
                .append(",\"createdAt\":").append(json(order.createdAt))
                .append(",\"status\":").append(json(order.status))
                .append(",\"deliveryMethod\":").append(json(order.deliveryMethod))
                .append(",\"paymentMethod\":").append(json(order.paymentMethod))
                .append(",\"subtotalCents\":").append(order.subtotalCents)
                .append(",\"protectionCents\":").append(order.protectionCents)
                .append(",\"deliveryFeeCents\":").append(order.deliveryFeeCents)
                .append(",\"totalCents\":").append(order.totalCents)
                .append(",\"lines\":[");
        for (int i = 0; i < order.lines.size(); i++) {
            if (i > 0) result.append(',');
            OrderLine line = order.lines.get(i);
            result.append("{\"productId\":").append(json(line.productId))
                    .append(",\"name\":").append(json(line.name))
                    .append(",\"emoji\":").append(json(line.emoji))
                    .append(",\"quantity\":").append(line.quantity)
                    .append(",\"priceCents\":").append(line.priceCents).append('}');
        }
        return result.append("]}").toString();
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"message\":" + json(message) + "}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private static final class StaticFile {
        private final Path path;
        private final String contentType;

        private StaticFile(Path path, String contentType) {
            this.path = path;
            this.contentType = contentType;
        }
    }

    private static final class Product {
        private final String id;
        private final String name;
        private final String category;
        private final String description;
        private String maker;
        private final String emoji;
        private final String flowerColor;
        private final String color;
        private final int priceCents;
        private int stock;
        private final String freshness;
        private final String sellerPhone;
        private final String sellerLocation;
        private String sellerName;
        private String sellerEmail;
        private final String ownerSubject;

        private Product(String id, String name, String category, String description, String maker,
                        String emoji, String flowerColor, String color, int priceCents, int stock,
                        String freshness, String sellerPhone, String sellerLocation, String sellerName,
                        String sellerEmail, String ownerSubject) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.description = description;
            this.maker = maker;
            this.emoji = emoji;
            this.flowerColor = flowerColor;
            this.color = color;
            this.priceCents = priceCents;
            this.stock = stock;
            this.freshness = freshness;
            this.sellerPhone = sellerPhone;
            this.sellerLocation = sellerLocation;
            this.sellerName = sellerName;
            this.sellerEmail = sellerEmail;
            this.ownerSubject = ownerSubject;
        }
    }

    private static final class OrderLine {
        private final String productId;
        private final String name;
        private final String emoji;
        private final int quantity;
        private final int priceCents;
        private final String sellerSubject;

        private OrderLine(String productId, String name, String emoji, int quantity, int priceCents,
                          String sellerSubject) {
            this.productId = productId;
            this.name = name;
            this.emoji = emoji;
            this.quantity = quantity;
            this.priceCents = priceCents;
            this.sellerSubject = sellerSubject;
        }
    }

    private static final class UserSession {
        private final String sessionId;
        private final String subject;
        private final String name;
        private final String email;
        private final String picture;
        private final String role;
        private final Instant expiresAt;

        private UserSession(String sessionId, String subject, String name, String email,
                            String picture, String role, Instant expiresAt) {
            this.sessionId = sessionId;
            this.subject = subject;
            this.name = name;
            this.email = email;
            this.picture = picture;
            this.role = role;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Order {
        private final String id;
        private final String customer;
        private final String email;
        private final String address;
        private final String createdAt;
        private String status;
        private final List<OrderLine> lines;
        private final long subtotalCents;
        private final long protectionCents;
        private final long deliveryFeeCents;
        private final String deliveryMethod;
        private final String paymentMethod;
        private final long totalCents;

        private Order(String id, String customer, String email, String address, String createdAt,
                      String status, List<OrderLine> lines, long subtotalCents, long protectionCents,
                      long deliveryFeeCents, String deliveryMethod, String paymentMethod, long totalCents) {
            this.id = id;
            this.customer = customer;
            this.email = email;
            this.address = address;
            this.createdAt = createdAt;
            this.status = status;
            this.lines = lines;
            this.subtotalCents = subtotalCents;
            this.protectionCents = protectionCents;
            this.deliveryFeeCents = deliveryFeeCents;
            this.deliveryMethod = deliveryMethod;
            this.paymentMethod = paymentMethod;
            this.totalCents = totalCents;
        }
    }

    private static final class Profile {
        private String shopName;
        private String owner;
        private String email;

        private Profile(String shopName, String owner, String email) {
            this.shopName = shopName;
            this.owner = owner;
            this.email = email;
        }
    }
}
