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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarketServer {
    private static final int MAX_BODY_BYTES = 32_768;
    private static final int MAX_PRODUCT_BODY_BYTES = 3_200_000;
    private static final int MAX_PRODUCT_IMAGE_BYTES = 2_000_000;
    private static final Path PAGE = Path.of("Untitled-1.html");
    private static final Map<String, StaticFile> STATIC_FILES = Map.of(
            "/manifest.webmanifest", new StaticFile(Path.of("manifest.webmanifest"), "application/manifest+json; charset=utf-8"),
            "/service-worker.js", new StaticFile(Path.of("service-worker.js"), "text/javascript; charset=utf-8"),
            "/icon.svg", new StaticFile(Path.of("icon.svg"), "image/svg+xml; charset=utf-8"));
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern DRIVE_FILE_PATH = Pattern.compile("^/file/d/([A-Za-z0-9_-]{10,200})(?:/.*)?$");
    private static final Pattern DRIVE_ID_QUERY = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]{10,200})(?:&|$)");
    private static final Set<String> CATEGORIES = Set.of("Rose", "Tulip", "Sunflower", "Lily", "Carnation", "Daisy", "Orchid", "Mixed Bouquet");
    private static final Set<String> FLOWER_COLORS = Set.of("Red", "Pink", "White", "Yellow", "Purple", "Blue", "Orange", "Mixed");
    private static final Set<String> EMOJIS = Set.of("💐", "🌹", "🌻", "🌷", "🌸", "🪷", "🌼");
    private static final Set<String> ORDER_STATUSES = Set.of("Pending", "Preparing", "Out for delivery", "Delivered");
    private static final Set<String> DELIVERY_METHODS = Set.of("Delivery", "Pick Up");
    private static final Set<String> PAYMENT_METHODS = Set.of("Cash on Delivery", "GCash");
    private static final Pattern JSON_STRING = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final String GOOGLE_CLIENT_ID = System.getenv().getOrDefault("GOOGLE_CLIENT_ID", "").strip();
    private static final Path DATA_DIRECTORY = Path.of(System.getenv().getOrDefault(
            "MADEFORU_DATA_DIR", Path.of(System.getProperty("user.home"), ".madeforu").toString()));
    private static final Path SELLER_PROFILES_FILE = DATA_DIRECTORY.resolve("seller-profiles.tsv");
    private static final Path MARKETPLACE_DATA_FILE = DATA_DIRECTORY.resolve("marketplace-data.tsv");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    private static final List<Product> PRODUCTS = new ArrayList<>();
    private static final List<Order> ORDERS = new ArrayList<>();
    private static final Map<String, UserSession> SESSIONS = new HashMap<>();
    private static final Map<String, Profile> SELLER_PROFILES = new HashMap<>();
    private static final Map<String, Conversation> CONVERSATIONS = new LinkedHashMap<>();
    private static final List<Review> REVIEWS = new ArrayList<>();
    private static final Map<String, Integer> CUSTOMER_PURCHASE_COUNTS = new HashMap<>();
    private static final Map<String, Instant> SELLER_SUBSCRIPTIONS = new HashMap<>();
    private static final Map<String, Set<String>> CUSTOMER_FOLLOWS = new HashMap<>();
    private static int nextProduct = 1007;
    private static int nextOrder = 1001;
    private static int nextConversation = 1001;

    private MarketServer() {}

    public static void main(String[] args) throws IOException {
        loadSellerProfiles();
        loadMarketplaceData();
        String configuredPort = args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "8080");
        int port = Integer.parseInt(configuredPort);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", MarketServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("MadeForU is listening on port " + port);
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
            } else if (path.startsWith("/api/sellers/") && "GET".equals(method)) {
                sendSellerProfile(exchange, path.substring("/api/sellers/".length()), getSession(exchange));
            } else if ("/api/sellers/follow".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "customer");
                if (user != null) updateSellerFollow(exchange, user);
            } else if ("/api/seller/subscription".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "seller");
                if (user != null) activateSellerSubscription(exchange, user);
            } else if ("/api/orders".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "customer");
                if (user != null) createOrder(exchange, user);
            } else if ("/api/reviews".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "customer");
                if (user != null) createReview(exchange, user);
            } else if ("/api/messages/send".equals(path) && "POST".equals(method)) {
                UserSession user = getSession(exchange);
                if (user == null) sendError(exchange, 401, "Please sign in to send a message.");
                else sendMessage(exchange, user);
            } else if ("/api/drive/verify".equals(path) && "POST".equals(method)) {
                UserSession user = requireSession(exchange, "customer");
                if (user != null) verifyDriveLink(exchange);
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
        if (Set.of("/api/orders", "/api/messages/send", "/api/drive/verify", "/api/seller/products", "/api/seller/products/delete",
                "/api/seller/orders/status", "/api/seller/profile", "/api/sellers/follow",
                "/api/seller/subscription").contains(path)) return "POST";
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
        String paymentConfirmation = field(fields, "sandboxPaymentConfirmed");
        double deliveryLatitude;
        double deliveryLongitude;
        try {
            deliveryLatitude = optionalCoordinate(fields, "deliveryLatitude");
            deliveryLongitude = optionalCoordinate(fields, "deliveryLongitude");
        } catch (NumberFormatException exception) {
            sendError(exchange, 400, "Choose a valid delivery location or leave the GPS pin empty.");
            return;
        }
        boolean giftMediaRequested = "yes".equals(fields.get("giftMediaRequested"));
        String giftMediaUrl = "";
        if (customer.isBlank() || customer.length() > 100 || !EMAIL.matcher(email).matches()
                || email.length() > 254 || address.isBlank() || address.length() > 200
                || !DELIVERY_METHODS.contains(deliveryMethod) || !PAYMENT_METHODS.contains(paymentMethod)
                || Double.isNaN(deliveryLatitude) != Double.isNaN(deliveryLongitude)
                || !Double.isNaN(deliveryLatitude) && !validCoordinates(deliveryLatitude, deliveryLongitude)) {
            sendError(exchange, 400, "Please enter a name, valid email, and delivery address.");
            return;
        }
        if ("GCash".equals(paymentMethod) && !"true".equals(paymentConfirmation)) {
            sendError(exchange, 400, "Complete the simulated GCash sandbox payment before placing your order.");
            return;
        }
        if (giftMediaRequested) {
            String submittedGiftUrl = field(fields, "giftMediaUrl");
            if (submittedGiftUrl.isBlank()) {
                sendError(exchange, 400, "Verify a public Google Drive photo/video link before placing this order.");
                return;
            }
            try {
                DriveMedia media = verifyPublicDriveMedia(submittedGiftUrl);
                if (media == null) {
                    sendError(exchange, 422, "The gift photo/video link is not publicly accessible. Set General access to “Anyone with the link” before placing your order.");
                    return;
                }
                giftMediaUrl = media.url;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sendError(exchange, 503, "Gift link verification was interrupted. Please try again.");
                return;
            } catch (IOException exception) {
                System.err.println("Gift media verification failed during order placement.");
                sendError(exchange, 503, "Google Drive could not verify the gift link. Please try again.");
                return;
            }
        }

        Map<String, Integer> requestedItems = parseItems(fields.getOrDefault("items", ""));
        String bouquetDetails = field(fields, "bouquetDetails").strip();
        if (bouquetDetails.length() > 1000) {
            sendError(exchange, 400, "Bouquet selections must be under 1000 characters.");
            return;
        }
        if (requestedItems == null || requestedItems.isEmpty()) {
            sendError(exchange, 400, "Your basket is empty or invalid.");
            return;
        }

        synchronized (LOCK) {
            List<OrderLine> lines = new ArrayList<>();
            long total = 0;
            int totalQuantity = 0;
            for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
                Product product = findProduct(entry.getKey());
                if (product == null || entry.getValue() < 1 || entry.getValue() > 99
                        || product.stock < entry.getValue()) {
                    sendError(exchange, 409, "A product in your basket is no longer available in that quantity.");
                    return;
                }
                lines.add(new OrderLine(product.id, product.name, product.emoji, entry.getValue(),
                        product.imageDataUrl, product.priceCents, product.ownerSubject, product.sellerId, product.maker,
                        product.sellerName, product.sellerEmail));
                total += (long) product.priceCents * entry.getValue();
                totalQuantity += entry.getValue();
            }
            long subtotal = total;
            long protection = 5_000;
            long deliveryFee = "Delivery".equals(deliveryMethod) ? 10_000 : 0;
            int priorPurchases = CUSTOMER_PURCHASE_COUNTS.getOrDefault(user.subject, 0);
            long marketplaceFee = priorPurchases >= 5 ? (long) totalQuantity * 5_000 : 0;
            total += protection + deliveryFee + marketplaceFee;
            String orderId = "MF-" + nextOrder++;
            OffsetDateTime createdAtTime = OffsetDateTime.now();
            String createdAt = createdAtTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"));
            Order order = new Order(orderId, customer, email, user.subject, address, createdAt, createdAtTime.toString(),
                    "Pending", lines, subtotal, protection, deliveryFee, marketplaceFee, deliveryMethod, paymentMethod,
                    "GCash".equals(paymentMethod) ? "Paid (simulated sandbox)" : "Due on delivery", total,
                    deliveryLatitude, deliveryLongitude, giftMediaUrl, bouquetDetails);
            for (OrderLine line : lines) order.sellerStatuses.putIfAbsent(line.sellerSubject, "Pending");
            ORDERS.add(order);
            CUSTOMER_PURCHASE_COUNTS.put(user.subject, priorPurchases + 1);
            try {
                saveMarketplaceData();
            } catch (IOException exception) {
                ORDERS.remove(order);
                CUSTOMER_PURCHASE_COUNTS.put(user.subject, priorPurchases);
                nextOrder--;
                System.err.println("Could not save customer purchase count.");
                sendError(exchange, 500, "Your order could not be saved. Please try again.");
                return;
            }
            for (Map.Entry<String, Integer> entry : requestedItems.entrySet()) {
                findProduct(entry.getKey()).stock -= entry.getValue();
            }
            createOrderConversations(order);
            String firstConversationId = CONVERSATIONS.values().stream()
                    .filter(conversation -> conversation.orderId.equals(orderId))
                    .map(conversation -> conversation.id).findFirst().orElse("");
            sendJson(exchange, 201, "{\"orderId\":" + json(orderId) + ",\"conversationId\":"
                    + json(firstConversationId) + ",\"message\":\"Order placed.\"}");
        }
    }

    private static void createOrderConversations(Order order) {
        Map<String, OrderLine> sellers = new LinkedHashMap<>();
        for (OrderLine line : order.lines) sellers.putIfAbsent(line.sellerSubject, line);
        for (Map.Entry<String, OrderLine> seller : sellers.entrySet()) {
            OrderLine line = seller.getValue();
            String id = "C-" + nextConversation++;
            Conversation conversation = new Conversation(id, order.id, seller.getKey(), line.sellerName,
                    line.sellerEmail, order.customerSubject, order.customer, order.email);
            conversation.messages.add(new ChatMessage("seller",
                    "Thank you for your purchase, " + order.customer + "! We appreciate your order and will prepare your fresh flowers with care.",
                    OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))));
            CONVERSATIONS.put(id, conversation);
        }
    }

    private static void sendMessage(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String conversationId = field(fields, "conversationId");
        String text = field(fields, "text");
        if (conversationId.isBlank() || text.isBlank() || text.length() > 1000) {
            sendError(exchange, 400, "Enter a message of 1 to 1000 characters.");
            return;
        }
        synchronized (LOCK) {
            Conversation conversation = CONVERSATIONS.get(conversationId);
            if (conversation == null || !conversation.includes(user)) {
                sendError(exchange, 404, "Conversation not found.");
                return;
            }
            conversation.messages.add(new ChatMessage(user.role, text,
                    OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))));
            sendJson(exchange, 201, "{\"message\":\"Message sent.\"}");
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
        String flowerColor = field(fields, "flowerColor");
        String emoji = field(fields, "emoji");
        String color = field(fields, "color");
        String freshness = field(fields, "freshness");
        String sellerPhone = field(fields, "sellerPhone");
        String imageDataUrl = field(fields, "imageDataUrl");
        byte[] imageBytes = decodeFlowerImage(imageDataUrl);
        int stock;
        int priceCents;
        String shopName;
        String publicId;
        String sellerName;
        String sellerEmail;
        String locationLabel;
        double latitude;
        double longitude;
        synchronized (LOCK) {
            Profile sellerProfile = SELLER_PROFILES.get(user.subject);
            shopName = sellerProfile == null ? "" : sellerProfile.shopName;
            publicId = sellerProfile == null ? "" : sellerProfile.publicId;
            sellerName = sellerProfile == null ? "" : sellerProfile.owner;
            sellerEmail = sellerProfile == null ? "" : sellerProfile.email;
            locationLabel = sellerProfile == null ? "" : sellerProfile.locationLabel;
            latitude = sellerProfile == null ? Double.NaN : sellerProfile.latitude;
            longitude = sellerProfile == null ? Double.NaN : sellerProfile.longitude;
        }
        if (shopName.isBlank()) {
            sendError(exchange, 400, "Please set up your shop name in Seller Profile before adding a flower listing.");
            return;
        }
        if (!validCoordinates(latitude, longitude)) {
            sendError(exchange, 400, "Capture and save your shop GPS location in Seller Profile before adding a flower listing.");
            return;
        }
        synchronized (LOCK) {
            if (!sellerSubscriptionIsActive(user.subject)) {
                sendError(exchange, 402, "Activate or renew your ₱400 seller subscription before publishing listings.");
                return;
            }
        }
        if (imageBytes == null) {
            sendError(exchange, 400, "Upload a valid cropped flower photo under 2 MB before publishing this listing.");
            return;
        }
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
                || sellerPhone.isBlank() || sellerPhone.length() > 30
                || !"true".equals(fields.get("handmade"))
                || !color.matches("#[0-9a-fA-F]{6}") || priceCents < 1 || priceCents > 9_999_999
                || stock < 1 || stock > 9999) {
            sendError(exchange, 400, "Please check the product details and try again.");
            return;
        }
        synchronized (LOCK) {
            String id = "P-" + nextProduct++;
            PRODUCTS.add(new Product(id, name, flowerType, description, shopName, publicId, emoji, flowerColor,
                    color, priceCents, stock, freshness, sellerPhone, imageDataUrl,
                    latitude, longitude, locationLabel,
                    sellerName, sellerEmail, user.subject));
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
                    order.sellerStatuses.put(user.subject, status);
                    order.status = overallOrderStatus(order);
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
        String locationLabel = field(fields, "locationLabel");
        boolean locationConfirmed = "true".equals(fields.get("locationConfirmed"));
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(field(fields, "latitude"));
            longitude = Double.parseDouble(field(fields, "longitude"));
        } catch (NumberFormatException exception) {
            sendError(exchange, 400, "Use your device’s GPS to capture your shop location before saving your profile.");
            return;
        }
        if (shopName.isBlank() || shopName.length() > 80 || owner.isBlank() || owner.length() > 80
                || email.length() > 254 || !EMAIL.matcher(email).matches()
                || !validCoordinates(latitude, longitude) || locationLabel.isBlank() || locationLabel.length() > 120
                || !locationConfirmed) {
            sendError(exchange, 400, "Confirm the shop location and check the profile details before saving.");
            return;
        }

        synchronized (LOCK) {
            Profile previousProfile = SELLER_PROFILES.get(user.subject);
            String previousShopName = previousProfile == null
                    ? user.name + "'s Flowers" : previousProfile.shopName;
            String publicId = previousProfile == null ? newPublicId() : previousProfile.publicId;
            Profile updatedProfile = new Profile(publicId, shopName, owner, email,
                    latitude, longitude, locationLabel);
            SELLER_PROFILES.put(user.subject, updatedProfile);
            try {
                saveSellerProfiles();
            } catch (IOException exception) {
                if (previousProfile == null) SELLER_PROFILES.remove(user.subject);
                else SELLER_PROFILES.put(user.subject, previousProfile);
                System.err.println("Could not save seller profile data.");
                sendError(exchange, 500, "Your shop profile could not be saved to storage. Please try again.");
                return;
            }
            for (Product product : PRODUCTS) {
                if (!user.subject.equals(product.ownerSubject)) continue;
                if (product.maker.equals(previousShopName)) product.maker = shopName;
                product.sellerName = owner;
                product.sellerEmail = email;
                product.latitude = latitude;
                product.longitude = longitude;
                product.sellerLocation = locationLabel;
            }
            sendJson(exchange, 200, "{\"message\":\"Profile saved.\"}");
        }
    }

    private static void loadSellerProfiles() throws IOException {
        if (!Files.isRegularFile(SELLER_PROFILES_FILE)) return;
        List<String> rows = Files.readAllLines(SELLER_PROFILES_FILE, StandardCharsets.UTF_8);
        for (int index = 0; index < rows.size(); index++) {
            String row = rows.get(index);
            if (row.isBlank()) continue;
            String[] fields = row.split("\\t", -1);
            if (fields.length != 8) {
                throw new IOException("Invalid seller profile data at line " + (index + 1) + ".");
            }
            try {
                String subject = decodeProfileValue(fields[0]);
                String publicId = decodeProfileValue(fields[1]);
                String shopName = decodeProfileValue(fields[2]);
                String owner = decodeProfileValue(fields[3]);
                String email = decodeProfileValue(fields[4]);
                double latitude = Double.parseDouble(fields[5]);
                double longitude = Double.parseDouble(fields[6]);
                String locationLabel = decodeProfileValue(fields[7]);
                if (subject.isBlank() || publicId.isBlank() || shopName.isBlank()
                        || !validCoordinates(latitude, longitude) || locationLabel.isBlank()) {
                    throw new IllegalArgumentException("Required seller profile fields are missing.");
                }
                SELLER_PROFILES.put(subject, new Profile(publicId, shopName, owner, email,
                        latitude, longitude, locationLabel));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid seller profile data at line " + (index + 1) + ".", exception);
            }
        }
    }

    private static void saveSellerProfiles() throws IOException {
        Files.createDirectories(DATA_DIRECTORY);
        Path temporaryFile = Files.createTempFile(DATA_DIRECTORY, "seller-profiles-", ".tmp");
        try {
            List<String> rows = new ArrayList<>();
            for (Map.Entry<String, Profile> entry : SELLER_PROFILES.entrySet()) {
                Profile profile = entry.getValue();
                rows.add(encodeProfileValue(entry.getKey()) + "\t"
                        + encodeProfileValue(profile.publicId) + "\t"
                        + encodeProfileValue(profile.shopName) + "\t"
                        + encodeProfileValue(profile.owner) + "\t"
                        + encodeProfileValue(profile.email) + "\t"
                        + profile.latitude + "\t" + profile.longitude + "\t"
                        + encodeProfileValue(profile.locationLabel));
            }
            Files.write(temporaryFile, rows, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, SELLER_PROFILES_FILE,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, SELLER_PROFILES_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static void loadMarketplaceData() throws IOException {
        if (!Files.isRegularFile(MARKETPLACE_DATA_FILE)) return;
        List<String> rows = Files.readAllLines(MARKETPLACE_DATA_FILE, StandardCharsets.UTF_8);
        for (int index = 0; index < rows.size(); index++) {
            String[] fields = rows.get(index).split("\\t", -1);
            try {
                switch (fields[0]) {
                    case "PURCHASE" -> {
                        if (fields.length != 3) throw new IllegalArgumentException();
                        int count = Integer.parseInt(fields[2]);
                        if (count < 0) throw new IllegalArgumentException();
                        CUSTOMER_PURCHASE_COUNTS.put(decodeProfileValue(fields[1]), count);
                    }
                    case "SUBSCRIPTION" -> {
                        if (fields.length != 3) throw new IllegalArgumentException();
                        SELLER_SUBSCRIPTIONS.put(decodeProfileValue(fields[1]), Instant.parse(fields[2]));
                    }
                    case "FOLLOW" -> {
                        if (fields.length != 3) throw new IllegalArgumentException();
                        CUSTOMER_FOLLOWS.computeIfAbsent(decodeProfileValue(fields[1]), ignored -> new HashSet<>())
                                .add(decodeProfileValue(fields[2]));
                    }
                    default -> throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid marketplace data at line " + (index + 1) + ".", exception);
            }
        }
    }

    private static void saveMarketplaceData() throws IOException {
        Files.createDirectories(DATA_DIRECTORY);
        Path temporaryFile = Files.createTempFile(DATA_DIRECTORY, "marketplace-data-", ".tmp");
        try {
            List<String> rows = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : CUSTOMER_PURCHASE_COUNTS.entrySet()) {
                rows.add("PURCHASE\t" + encodeProfileValue(entry.getKey()) + "\t" + entry.getValue());
            }
            for (Map.Entry<String, Instant> entry : SELLER_SUBSCRIPTIONS.entrySet()) {
                rows.add("SUBSCRIPTION\t" + encodeProfileValue(entry.getKey()) + "\t" + entry.getValue());
            }
            for (Map.Entry<String, Set<String>> entry : CUSTOMER_FOLLOWS.entrySet()) {
                for (String sellerId : entry.getValue()) {
                    rows.add("FOLLOW\t" + encodeProfileValue(entry.getKey()) + "\t" + encodeProfileValue(sellerId));
                }
            }
            Files.write(temporaryFile, rows, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, MARKETPLACE_DATA_FILE,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, MARKETPLACE_DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static boolean sellerSubscriptionIsActive(String subject) {
        Instant expiresAt = SELLER_SUBSCRIPTIONS.get(subject);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    private static void activateSellerSubscription(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        if (!"true".equals(fields.get("sandboxPaymentConfirmed"))) {
            sendError(exchange, 400, "Confirm the simulated GCash sandbox payment to activate your subscription.");
            return;
        }
        synchronized (LOCK) {
            Instant previous = SELLER_SUBSCRIPTIONS.get(user.subject);
            Instant start = previous != null && previous.isAfter(Instant.now()) ? previous : Instant.now();
            Instant expiresAt = start.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
            SELLER_SUBSCRIPTIONS.put(user.subject, expiresAt);
            try {
                saveMarketplaceData();
            } catch (IOException exception) {
                if (previous == null) SELLER_SUBSCRIPTIONS.remove(user.subject);
                else SELLER_SUBSCRIPTIONS.put(user.subject, previous);
                System.err.println("Could not save seller subscription data.");
                sendError(exchange, 500, "The seller subscription could not be saved. Please try again.");
                return;
            }
            sendJson(exchange, 200, "{\"active\":true,\"expiresAt\":" + json(expiresAt.toString())
                    + ",\"message\":\"Simulated sandbox subscription activated.\"}");
        }
    }

    private static void updateSellerFollow(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String sellerId = field(fields, "sellerId");
        String followValue = field(fields, "follow");
        if (!Set.of("true", "false").contains(followValue)) {
            sendError(exchange, 400, "Choose whether to follow or unfollow this shop.");
            return;
        }
        boolean follow = "true".equals(followValue);
        synchronized (LOCK) {
            boolean sellerExists = SELLER_PROFILES.values().stream().anyMatch(profile -> profile.publicId.equals(sellerId));
            if (!sellerExists || sellerId.isBlank()) {
                sendError(exchange, 404, "Seller profile not found.");
                return;
            }
            Set<String> followed = CUSTOMER_FOLLOWS.computeIfAbsent(user.subject, ignored -> new HashSet<>());
            boolean wasFollowing = followed.contains(sellerId);
            if (follow) followed.add(sellerId);
            else followed.remove(sellerId);
            try {
                saveMarketplaceData();
            } catch (IOException exception) {
                if (wasFollowing) followed.add(sellerId);
                else followed.remove(sellerId);
                if (followed.isEmpty()) CUSTOMER_FOLLOWS.remove(user.subject);
                System.err.println("Could not save customer follow data.");
                sendError(exchange, 500, "Your seller follow list could not be saved. Please try again.");
                return;
            }
            sendJson(exchange, 200, "{\"following\":" + follow + "}");
        }
    }

    private static String encodeProfileValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeProfileValue(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void createReview(HttpExchange exchange, UserSession user) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String orderId = field(fields, "orderId");
        String sellerId = field(fields, "sellerId");
        String comment = field(fields, "comment").strip();
        int rating;
        try {
            rating = Integer.parseInt(field(fields, "rating"));
        } catch (NumberFormatException exception) {
            sendError(exchange, 400, "Choose a rating from 1 to 5 stars.");
            return;
        }
        if (orderId.isBlank() || sellerId.isBlank() || rating < 1 || rating > 5 || comment.length() > 1000) {
            sendError(exchange, 400, "Enter a rating from 1 to 5 stars and a review under 1000 characters.");
            return;
        }
        synchronized (LOCK) {
            Order order = ORDERS.stream().filter(item -> item.id.equals(orderId)
                    && user.subject.equals(item.customerSubject)).findFirst().orElse(null);
            if (order == null) {
                sendError(exchange, 404, "That order was not found in your customer account.");
                return;
            }
            String sellerSubject = order.lines.stream()
                    .filter(line -> sellerId.equals(line.sellerId))
                    .map(line -> line.sellerSubject).findFirst().orElse("");
            if (sellerSubject.isBlank() || !order.sellerStatuses.containsKey(sellerSubject)
                    || !sellerId.equals(findSellerId(sellerSubject))) {
                sendError(exchange, 403, "You can only review a seller included in this order.");
                return;
            }
            if (!"Delivered".equals(order.sellerStatuses.get(sellerSubject))) {
                sendError(exchange, 409, "You can review this seller after your order is marked delivered.");
                return;
            }
            boolean alreadyReviewed = REVIEWS.stream().anyMatch(review ->
                    review.orderId.equals(order.id) && review.sellerSubject.equals(sellerSubject)
                            && review.customerSubject.equals(user.subject));
            if (alreadyReviewed) {
                sendError(exchange, 409, "You have already reviewed this seller for this order.");
                return;
            }
            REVIEWS.add(new Review(newPublicId(), sellerSubject, order.id, user.subject,
                    user.name, rating, comment,
                    OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))));
            sendJson(exchange, 201, "{\"message\":\"Your review was posted.\"}");
        }
    }

    private static String findSellerId(String sellerSubject) {
        Profile profile = SELLER_PROFILES.get(sellerSubject);
        return profile == null ? "" : profile.publicId;
    }

    private static String overallOrderStatus(Order order) {
        if (order.sellerStatuses.values().stream().allMatch("Delivered"::equals)) return "Delivered";
        if (order.sellerStatuses.containsValue("Out for delivery")) return "Out for delivery";
        if (order.sellerStatuses.containsValue("Preparing")) return "Preparing";
        return "Pending";
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            sendError(exchange, 415, "Expected URL-encoded form data.");
            return null;
        }
        int bodyLimit = "/api/seller/products".equals(exchange.getRequestURI().getPath())
                ? MAX_PRODUCT_BODY_BYTES : MAX_BODY_BYTES;
        byte[] body = exchange.getRequestBody().readNBytes(bodyLimit + 1);
        if (body.length > bodyLimit) {
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

    private static byte[] decodeFlowerImage(String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix) || dataUrl.length() > 2_667_000) return null;
        try {
            byte[] image = Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
            if (image.length < 3 || image.length > MAX_PRODUCT_IMAGE_BYTES
                    || (image[0] & 0xff) != 0xff || (image[1] & 0xff) != 0xd8 || (image[2] & 0xff) != 0xff) {
                return null;
            }
            return image;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private static double optionalCoordinate(Map<String, String> fields, String name) {
        String value = field(fields, name);
        return value.isBlank() ? Double.NaN : Double.parseDouble(value);
    }

    private static void verifyDriveLink(HttpExchange exchange) throws IOException {
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String link = field(fields, "url");
        if (link.isBlank() || link.length() > 2000) {
            sendError(exchange, 400, "Paste a Google Drive link to a photo or video.");
            return;
        }
        try {
            DriveMedia media = verifyPublicDriveMedia(link);
            if (media == null) {
                sendError(exchange, 422, "This Drive file is not publicly accessible as a photo or video. Set General access to “Anyone with the link” and try again.");
                return;
            }
            sendJson(exchange, 200, "{\"url\":" + json(media.url) + ",\"mimeType\":" + json(media.mimeType)
                    + ",\"message\":\"Public photo/video link verified.\"}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "Public link verification was interrupted. Please try again.");
        } catch (IOException exception) {
            System.err.println("Public Google Drive link verification failed.");
            sendError(exchange, 503, "Google Drive could not verify the link right now. Please try again.");
        }
    }

    private static DriveMedia verifyPublicDriveMedia(String link) throws IOException, InterruptedException {
        URI supplied;
        try {
            supplied = URI.create(link);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (!"https".equalsIgnoreCase(supplied.getScheme())
                || !"drive.google.com".equalsIgnoreCase(supplied.getHost())
                || supplied.getUserInfo() != null || (supplied.getPort() != -1 && supplied.getPort() != 443)) {
            return null;
        }

        String fileId = "";
        Matcher pathMatcher = DRIVE_FILE_PATH.matcher(supplied.getPath());
        if (pathMatcher.matches()) {
            fileId = pathMatcher.group(1);
        } else if (supplied.getRawQuery() != null) {
            Matcher queryMatcher = DRIVE_ID_QUERY.matcher(supplied.getRawQuery());
            if (queryMatcher.find()) fileId = queryMatcher.group(1);
        }
        if (fileId.isBlank()) return null;

        URI downloadUri = URI.create("https://drive.google.com/uc?export=download&id=" + fileId);
        HttpRequest request = HttpRequest.newBuilder(downloadUri)
                .timeout(Duration.ofSeconds(10))
                .header("Range", "bytes=0-0")
                .GET().build();
        HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        String mimeType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].strip().toLowerCase();
        boolean mediaType = mimeType.startsWith("image/") || mimeType.startsWith("video/");
        if ((response.statusCode() != 200 && response.statusCode() != 206) || !mediaType) return null;
        return new DriveMedia("https://drive.google.com/file/d/" + fileId + "/view?usp=sharing", mimeType);
    }

    private static Product findProduct(String id) {
        for (Product product : PRODUCTS) {
            if (product.id.equals(id)) return product;
        }
        return null;
    }

    private static String newPublicId() {
        byte[] random = new byte[18];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String stateJson(UserSession user) {
        synchronized (LOCK) {
            StringBuilder result = new StringBuilder("{\"sellers\":[");
            boolean firstSeller = true;
            for (Map.Entry<String, Profile> entry : SELLER_PROFILES.entrySet()) {
                if (!firstSeller) result.append(',');
                result.append(sellerSummaryJson(entry.getKey(), entry.getValue()));
                firstSeller = false;
            }
            result.append("],\"products\":[");
            for (int i = 0; i < PRODUCTS.size(); i++) {
                if (i > 0) result.append(',');
                result.append(productJson(PRODUCTS.get(i), user));
            }
            result.append("],\"orders\":[");
            boolean firstOrder = true;
            for (Order order : ORDERS) {
                boolean visible = user != null && ("customer".equals(user.role)
                        ? user.subject.equals(order.customerSubject)
                        : order.lines.stream().anyMatch(line -> user.subject.equals(line.sellerSubject)));
                if (visible) {
                    if (!firstOrder) result.append(',');
                    result.append(orderJson(order, user));
                    firstOrder = false;
                }
            }
            result.append("],\"profile\":");
            Profile profile = user != null && "seller".equals(user.role)
                    ? SELLER_PROFILES.get(user.subject) : null;
            if (user == null) {
                result.append("null");
            } else if ("customer".equals(user.role)) {
                result.append("{\"followingCount\":")
                        .append(CUSTOMER_FOLLOWS.getOrDefault(user.subject, Set.of()).size()).append('}');
            } else {
                Instant subscriptionExpiry = SELLER_SUBSCRIPTIONS.get(user.subject);
                result.append("{\"shopName\":").append(json(profile == null ? "" : profile.shopName))
                        .append(",\"sellerId\":").append(json(profile == null ? "" : profile.publicId))
                        .append(",\"owner\":").append(json(profile == null ? "" : profile.owner))
                        .append(",\"email\":").append(json(profile == null ? "" : profile.email))
                        .append(",\"latitude\":").append(profile == null ? "null" : profile.latitude)
                        .append(",\"longitude\":").append(profile == null ? "null" : profile.longitude)
                        .append(",\"locationLabel\":").append(json(profile == null ? "" : profile.locationLabel))
                        .append(",\"subscriptionActive\":").append(sellerSubscriptionIsActive(user.subject))
                        .append(",\"subscriptionExpiresAt\":")
                        .append(subscriptionExpiry == null ? "null" : json(subscriptionExpiry.toString()))
                        .append(",\"averageRatingHundredths\":").append(sellerAverageRating(user.subject))
                        .append('}');
            }
            result.append(",\"customerPurchaseCount\":")
                    .append(user == null || !"customer".equals(user.role)
                            ? 0 : CUSTOMER_PURCHASE_COUNTS.getOrDefault(user.subject, 0));
            result.append(",\"followedSellerIds\":[");
            boolean firstFollow = true;
            if (user != null && "customer".equals(user.role)) {
                for (String sellerId : CUSTOMER_FOLLOWS.getOrDefault(user.subject, Set.of())) {
                    if (!firstFollow) result.append(',');
                    result.append(json(sellerId));
                    firstFollow = false;
                }
            }
            result.append(']');
            result.append(",\"conversations\":[");
            boolean firstConversation = true;
            if (user != null) {
                for (Conversation conversation : CONVERSATIONS.values()) {
                    if (!conversation.includes(user)) continue;
                    if (!firstConversation) result.append(',');
                    result.append(conversationJson(conversation, user));
                    firstConversation = false;
                }
            }
            result.append("]}");
            return result.toString();
        }
    }

    private static void sendSellerProfile(HttpExchange exchange, String sellerId, UserSession user) throws IOException {
        synchronized (LOCK) {
            Map.Entry<String, Profile> seller = SELLER_PROFILES.entrySet().stream()
                    .filter(entry -> entry.getValue().publicId.equals(sellerId)).findFirst().orElse(null);
            if (seller == null) {
                sendError(exchange, 404, "Seller profile not found.");
                return;
            }
            Profile profile = seller.getValue();
            boolean followed = user != null && "customer".equals(user.role)
                    && CUSTOMER_FOLLOWS.getOrDefault(user.subject, Set.of()).contains(profile.publicId);
            StringBuilder result = new StringBuilder("{\"seller\":")
                    .append(sellerSummaryJson(seller.getKey(), profile))
                    .append(",\"isFollowed\":").append(followed).append(",\"products\":[");
            boolean firstProduct = true;
            for (Product product : PRODUCTS) {
                if (!seller.getKey().equals(product.ownerSubject)) continue;
                if (!firstProduct) result.append(',');
                result.append(productJson(product, null));
                firstProduct = false;
            }
            result.append("],\"reviews\":[");
            boolean firstReview = true;
            for (int i = REVIEWS.size() - 1; i >= 0; i--) {
                Review review = REVIEWS.get(i);
                if (!seller.getKey().equals(review.sellerSubject)) continue;
                if (!firstReview) result.append(',');
                result.append(reviewJson(review));
                firstReview = false;
            }
            sendJson(exchange, 200, result.append("]}").toString());
        }
    }

    private static String sellerSummaryJson(String subject, Profile profile) {
        int listingCount = 0;
        String location = "";
        for (Product product : PRODUCTS) {
            if (!subject.equals(product.ownerSubject)) continue;
            listingCount++;
            if (location.isBlank()) location = product.sellerLocation;
        }
        int reviewCount = sellerReviewCount(subject);
        int averageRatingHundredths = sellerAverageRating(subject);
        return "{\"sellerId\":" + json(profile.publicId)
                + ",\"shopName\":" + json(profile.shopName)
                + ",\"owner\":" + json(profile.owner)
                + ",\"location\":" + json(profile.locationLabel.isBlank()
                        ? (location.isBlank() ? "Shop location" : location) : profile.locationLabel)
                + ",\"latitude\":" + profile.latitude
                + ",\"longitude\":" + profile.longitude
                + ",\"listingCount\":" + listingCount
                + ",\"followersCount\":" + sellerFollowersCount(profile.publicId)
                + ",\"reviewCount\":" + reviewCount
                + ",\"averageRatingHundredths\":" + averageRatingHundredths + "}";
    }

    private static int sellerReviewCount(String subject) {
        return (int) REVIEWS.stream().filter(review -> subject.equals(review.sellerSubject)).count();
    }

    private static int sellerAverageRating(String subject) {
        int total = 0;
        int count = 0;
        for (Review review : REVIEWS) {
            if (!subject.equals(review.sellerSubject)) continue;
            total += review.rating;
            count++;
        }
        return count == 0 ? 0 : (int) Math.round(total * 100.0 / count);
    }

    private static int sellerFollowersCount(String sellerId) {
        return (int) CUSTOMER_FOLLOWS.values().stream().filter(followed -> followed.contains(sellerId)).count();
    }

    private static String reviewJson(Review review) {
        return "{\"id\":" + json(review.id)
                + ",\"customerName\":" + json(review.customerName)
                + ",\"rating\":" + review.rating
                + ",\"comment\":" + json(review.comment)
                + ",\"createdAt\":" + json(review.createdAt) + "}";
    }

    private static String conversationJson(Conversation conversation, UserSession user) {
        String otherName = "seller".equals(user.role) ? conversation.customerName : conversation.sellerName;
        String otherEmail = "seller".equals(user.role) ? conversation.customerEmail : conversation.sellerEmail;
        StringBuilder result = new StringBuilder("{\"id\":").append(json(conversation.id))
                .append(",\"orderId\":").append(json(conversation.orderId))
                .append(",\"otherName\":").append(json(otherName))
                .append(",\"otherEmail\":").append(json(otherEmail))
                .append(",\"messages\":[");
        for (int i = 0; i < conversation.messages.size(); i++) {
            if (i > 0) result.append(',');
            ChatMessage message = conversation.messages.get(i);
            result.append("{\"senderRole\":").append(json(message.senderRole))
                    .append(",\"text\":").append(json(message.text))
                    .append(",\"createdAt\":").append(json(message.createdAt)).append('}');
        }
        return result.append("]}").toString();
    }

    private static String productJson(Product product, UserSession user) {
        return "{\"id\":" + json(product.id)
                + ",\"name\":" + json(product.name)
                + ",\"category\":" + json(product.category)
                + ",\"flowerType\":" + json(product.category)
                + ",\"description\":" + json(product.description)
                + ",\"maker\":" + json(product.maker)
                + ",\"sellerId\":" + json(product.sellerId)
                + ",\"sellerName\":" + json(product.sellerName)
                + ",\"sellerEmail\":" + json(product.sellerEmail)
                + ",\"sellerPhone\":" + json(product.sellerPhone)
                + ",\"sellerLocation\":" + json(product.sellerLocation)
                + ",\"latitude\":" + product.latitude
                + ",\"longitude\":" + product.longitude
                + ",\"ownedByCurrentSeller\":" + (user != null && user.subject.equals(product.ownerSubject))
                + ",\"emoji\":" + json(product.emoji)
                + ",\"imageDataUrl\":" + json(product.imageDataUrl)
                + ",\"flowerColor\":" + json(product.flowerColor)
                + ",\"color\":" + json(product.color)
                + ",\"freshness\":" + json(product.freshness)
                + ",\"handmade\":true"
                + ",\"priceCents\":" + product.priceCents
                + ",\"stock\":" + product.stock + "}";
    }

    private static String orderJson(Order order, UserSession user) {
        int sellerQuantity = 0;
        long sellerSubtotalCents = 0;
        if ("seller".equals(user.role)) {
            for (OrderLine line : order.lines) {
                if (user.subject.equals(line.sellerSubject)) {
                    sellerQuantity += line.quantity;
                    sellerSubtotalCents += (long) line.priceCents * line.quantity;
                }
            }
        }
        StringBuilder result = new StringBuilder("{\"id\":").append(json(order.id))
                .append(",\"customer\":").append(json(order.customer))
                .append(",\"email\":").append(json(order.email))
                .append(",\"address\":").append(json(order.address))
                .append(",\"deliveryLatitude\":").append(Double.isNaN(order.deliveryLatitude) ? "null" : order.deliveryLatitude)
                .append(",\"deliveryLongitude\":").append(Double.isNaN(order.deliveryLongitude) ? "null" : order.deliveryLongitude)
                .append(",\"createdAt\":").append(json(order.createdAt))
                .append(",\"createdAtIso\":").append(json(order.createdAtIso))
                .append(",\"status\":").append(json("seller".equals(user.role)
                        ? order.sellerStatuses.getOrDefault(user.subject, order.status) : order.status))
                .append(",\"deliveryMethod\":").append(json(order.deliveryMethod))
                .append(",\"paymentMethod\":").append(json(order.paymentMethod))
                .append(",\"paymentStatus\":").append(json(order.paymentStatus))
                .append(",\"subtotalCents\":").append("seller".equals(user.role) ? sellerSubtotalCents : order.subtotalCents)
                .append(",\"protectionCents\":").append("seller".equals(user.role) ? 0 : order.protectionCents)
                .append(",\"deliveryFeeCents\":").append("seller".equals(user.role) ? 0 : order.deliveryFeeCents)
                .append(",\"marketplaceFeeCents\":").append("seller".equals(user.role) ? 0 : order.marketplaceFeeCents)
                .append(",\"totalCents\":").append("seller".equals(user.role) ? sellerSubtotalCents : order.totalCents)
                .append(",\"sellerQuantity\":").append(sellerQuantity)
                .append(",\"sellerSubtotalCents\":").append(sellerSubtotalCents)
                .append(",\"giftMediaUrl\":").append(json(order.giftMediaUrl))
                .append(",\"bouquetDetails\":").append(json(order.bouquetDetails))
                .append(",\"lines\":[");
        boolean firstLine = true;
        for (OrderLine line : order.lines) {
            if ("seller".equals(user.role) && !user.subject.equals(line.sellerSubject)) continue;
            if (!firstLine) result.append(',');
            firstLine = false;
            result.append("{\"productId\":").append(json(line.productId))
                    .append(",\"name\":").append(json(line.name))
                    .append(",\"emoji\":").append(json(line.emoji))
                    .append(",\"imageDataUrl\":").append(json(line.imageDataUrl))
                    .append(",\"quantity\":").append(line.quantity)
                    .append(",\"priceCents\":").append(line.priceCents)
                    .append(",\"sellerId\":").append(json(line.sellerId))
                    .append(",\"sellerShopName\":").append(json(line.sellerShopName))
                    .append(",\"deliveryStatus\":").append(json(order.sellerStatuses.getOrDefault(line.sellerSubject, order.status)))
                    .append(",\"reviewed\":").append(REVIEWS.stream().anyMatch(review ->
                            review.orderId.equals(order.id) && review.sellerSubject.equals(line.sellerSubject)
                                    && review.customerSubject.equals(order.customerSubject)))
                    .append(",\"sellerName\":").append(json(line.sellerName))
                    .append(",\"sellerEmail\":").append(json(line.sellerEmail)).append('}');
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

    private static final class DriveMedia {
        private final String url;
        private final String mimeType;

        private DriveMedia(String url, String mimeType) {
            this.url = url;
            this.mimeType = mimeType;
        }
    }

    private static final class Product {
        private final String id;
        private final String name;
        private final String category;
        private final String description;
        private String maker;
        private final String sellerId;
        private final String emoji;
        private final String flowerColor;
        private final String color;
        private final int priceCents;
        private int stock;
        private final String freshness;
        private final String sellerPhone;
        private final String imageDataUrl;
        private double latitude;
        private double longitude;
        private String sellerLocation;
        private String sellerName;
        private String sellerEmail;
        private final String ownerSubject;

        private Product(String id, String name, String category, String description, String maker,
                        String sellerId, String emoji, String flowerColor, String color, int priceCents, int stock,
                        String freshness, String sellerPhone, String imageDataUrl, double latitude,
                        double longitude, String sellerLocation, String sellerName, String sellerEmail,
                        String ownerSubject) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.description = description;
            this.maker = maker;
            this.sellerId = sellerId;
            this.emoji = emoji;
            this.flowerColor = flowerColor;
            this.color = color;
            this.priceCents = priceCents;
            this.stock = stock;
            this.freshness = freshness;
            this.sellerPhone = sellerPhone;
            this.imageDataUrl = imageDataUrl;
            this.latitude = latitude;
            this.longitude = longitude;
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
        private final String imageDataUrl;
        private final int quantity;
        private final int priceCents;
        private final String sellerSubject;
        private final String sellerId;
        private final String sellerShopName;
        private final String sellerName;
        private final String sellerEmail;

        private OrderLine(String productId, String name, String emoji, int quantity, String imageDataUrl, int priceCents,
                          String sellerSubject, String sellerId, String sellerShopName,
                          String sellerName, String sellerEmail) {
            this.productId = productId;
            this.name = name;
            this.emoji = emoji;
            this.imageDataUrl = imageDataUrl;
            this.quantity = quantity;
            this.priceCents = priceCents;
            this.sellerSubject = sellerSubject;
            this.sellerId = sellerId;
            this.sellerShopName = sellerShopName;
            this.sellerName = sellerName;
            this.sellerEmail = sellerEmail;
        }
    }

    private static final class Conversation {
        private final String id;
        private final String orderId;
        private final String sellerSubject;
        private final String sellerName;
        private final String sellerEmail;
        private final String customerSubject;
        private final String customerName;
        private final String customerEmail;
        private final List<ChatMessage> messages = new ArrayList<>();

        private Conversation(String id, String orderId, String sellerSubject, String sellerName,
                             String sellerEmail, String customerSubject, String customerName, String customerEmail) {
            this.id = id;
            this.orderId = orderId;
            this.sellerSubject = sellerSubject;
            this.sellerName = sellerName;
            this.sellerEmail = sellerEmail;
            this.customerSubject = customerSubject;
            this.customerName = customerName;
            this.customerEmail = customerEmail;
        }

        private boolean includes(UserSession user) {
            return "seller".equals(user.role)
                    ? sellerSubject.equals(user.subject)
                    : "customer".equals(user.role) && customerSubject.equals(user.subject);
        }
    }

    private static final class ChatMessage {
        private final String senderRole;
        private final String text;
        private final String createdAt;

        private ChatMessage(String senderRole, String text, String createdAt) {
            this.senderRole = senderRole;
            this.text = text;
            this.createdAt = createdAt;
        }
    }

    private static final class Review {
        private final String id;
        private final String sellerSubject;
        private final String orderId;
        private final String customerSubject;
        private final String customerName;
        private final int rating;
        private final String comment;
        private final String createdAt;

        private Review(String id, String sellerSubject, String orderId,
                       String customerSubject, String customerName, int rating, String comment,
                       String createdAt) {
            this.id = id;
            this.sellerSubject = sellerSubject;
            this.orderId = orderId;
            this.customerSubject = customerSubject;
            this.customerName = customerName;
            this.rating = rating;
            this.comment = comment;
            this.createdAt = createdAt;
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
        private final String customerSubject;
        private final String address;
        private final String createdAt;
        private final String createdAtIso;
        private String status;
        private final List<OrderLine> lines;
        private final long subtotalCents;
        private final long protectionCents;
        private final long deliveryFeeCents;
        private final long marketplaceFeeCents;
        private final String deliveryMethod;
        private final String paymentMethod;
        private final String paymentStatus;
        private final long totalCents;
        private final double deliveryLatitude;
        private final double deliveryLongitude;
        private final String giftMediaUrl;
        private final String bouquetDetails;
        private final Map<String, String> sellerStatuses = new HashMap<>();

        private Order(String id, String customer, String email, String customerSubject, String address, String createdAt,
                      String createdAtIso,
                      String status, List<OrderLine> lines, long subtotalCents, long protectionCents,
                      long deliveryFeeCents, long marketplaceFeeCents, String deliveryMethod, String paymentMethod,
                      String paymentStatus, long totalCents, double deliveryLatitude, double deliveryLongitude,
                      String giftMediaUrl, String bouquetDetails) {
            this.id = id;
            this.customer = customer;
            this.email = email;
            this.customerSubject = customerSubject;
            this.address = address;
            this.createdAt = createdAt;
            this.createdAtIso = createdAtIso;
            this.status = status;
            this.lines = lines;
            this.subtotalCents = subtotalCents;
            this.protectionCents = protectionCents;
            this.deliveryFeeCents = deliveryFeeCents;
            this.marketplaceFeeCents = marketplaceFeeCents;
            this.deliveryMethod = deliveryMethod;
            this.paymentMethod = paymentMethod;
            this.paymentStatus = paymentStatus;
            this.totalCents = totalCents;
            this.deliveryLatitude = deliveryLatitude;
            this.deliveryLongitude = deliveryLongitude;
            this.giftMediaUrl = giftMediaUrl;
            this.bouquetDetails = bouquetDetails;
        }
    }

    private static final class Profile {
        private final String publicId;
        private String shopName;
        private String owner;
        private String email;
        private final double latitude;
        private final double longitude;
        private final String locationLabel;

        private Profile(String publicId, String shopName, String owner, String email,
                        double latitude, double longitude, String locationLabel) {
            this.publicId = publicId;
            this.shopName = shopName;
            this.owner = owner;
            this.email = email;
            this.latitude = latitude;
            this.longitude = longitude;
            this.locationLabel = locationLabel;
        }
    }
}
