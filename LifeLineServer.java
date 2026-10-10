import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * LifeLine Smart Blood Bank prototype (college project).
 * Uses Java's built-in HTTP server: no Spring Boot, Maven or database needed.
 * Data is saved to lifeline-data.ser so it survives a restart.
 */
public class LifeLineServer {
    private static final int PORT = 8091;
    private static final String DATA_FILE = "lifeline-data.ser";
    private static final int LOW_STOCK = 10;
    private static final int MAX_BODY = 20_000; // bytes
    private static final Set<String> GROUPS = Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    private static final Set<String> URGENCY = Set.of("URGENT", "HIGH", "NORMAL");

    private static final List<BloodStock> STOCK = new CopyOnWriteArrayList<>();
    private static final List<Donor> DONORS = new CopyOnWriteArrayList<>();
    private static final List<EmergencyRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<Map<String, String>> USERS = new CopyOnWriteArrayList<>();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>(); // token -> email

    private static final Object LOCK = new Object();
    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicLong NEXT_ID = new AtomicLong(System.currentTimeMillis());

    // ---------- data classes ----------
    static class BloodStock implements Serializable {
        private static final long serialVersionUID = 2L;
        String group, bank, location; volatile int units;
        BloodStock(String g, int u, String b, String l) { group = g; units = u; bank = b; location = l; }
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bloodGroup", group); m.put("units", units); m.put("bank", bank); m.put("location", location);
            m.put("status", units < LOW_STOCK ? "Low stock" : "Available");
            return m;
        }
    }

    static class Donor implements Serializable {
        private static final long serialVersionUID = 2L;
        String name, group, location, email; boolean available;
        Donor(String n, String g, String l, String e, boolean a) { name = n; group = g; location = l; email = e; available = a; }
    }

    static class EmergencyRequest implements Serializable {
        private static final long serialVersionUID = 2L;
        String group, hospital, location, urgency, contact; int units; long id;
        EmergencyRequest(long i, String g, int u, String h, String l, String ur, String c) {
            id = i; group = g; units = u; hospital = h; location = l; urgency = ur; contact = c;
        }
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("bloodGroup", group); m.put("units", units); m.put("hospital", hospital);
            m.put("location", location); m.put("urgency", urgency); m.put("contact", contact);
            return m;
        }
    }

    // ---------- startup ----------
    public static void main(String[] args) throws Exception {
        if (!loadData()) seedData();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/", LifeLineServer::api);
        server.createContext("/", LifeLineServer::staticFiles);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("LifeLine is running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    // ---------- persistence ----------
    /** Writes to a temp file first, then swaps it in, so a crash cannot leave a half-written data file. */
    private static synchronized void saveData() {
        Path tmp = Paths.get(DATA_FILE + ".tmp");
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(new ArrayList<>(USERS)); out.writeObject(new ArrayList<>(DONORS));
                out.writeObject(new ArrayList<>(REQUESTS)); out.writeObject(new ArrayList<>(STOCK));
            }
            try {
                Files.move(tmp, Paths.get(DATA_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, Paths.get(DATA_FILE), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) { System.err.println("Could not save data: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private static synchronized boolean loadData() {
        Path file = Paths.get(DATA_FILE);
        if (!Files.exists(file)) return false;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            USERS.addAll((List<Map<String, String>>) in.readObject());
            DONORS.addAll((List<Donor>) in.readObject());
            REQUESTS.addAll((List<EmergencyRequest>) in.readObject());
            STOCK.addAll((List<BloodStock>) in.readObject());
            // Upgrade old files that stored plain-text passwords.
            for (Map<String, String> u : USERS) {
                if (u.containsKey("password")) { u.put("passHash", hashPassword(u.remove("password"))); }
            }
            return true;
        } catch (Exception e) {
            USERS.clear(); DONORS.clear(); REQUESTS.clear(); STOCK.clear();
            try { Files.move(file, Paths.get(DATA_FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            System.err.println("Saved data could not be loaded (old format?). It was kept as " + DATA_FILE + ".bak; starting with demo data.");
            return false;
        }
    }

    private static void seedData() {
        String adminPassword = System.getenv("LIFELINE_ADMIN_PASSWORD");
        if (adminPassword == null || adminPassword.length() < 6) {
            adminPassword = "admin123";
            System.out.println("NOTE: using the default demo admin password. Set LIFELINE_ADMIN_PASSWORD to change it.");
        }
        Map<String, String> admin = new HashMap<>();
        admin.put("name", "LifeLine Admin"); admin.put("email", "admin@lifeline.com");
        admin.put("passHash", hashPassword(adminPassword)); admin.put("role", "ADMIN");
        admin.put("phone", ""); admin.put("location", "LifeLine");
        USERS.add(admin);
        STOCK.add(new BloodStock("A+", 18, "City Care Blood Bank", "Gudiyatham"));
        STOCK.add(new BloodStock("A-", 7, "City Care Blood Bank", "Gudiyatham"));
        STOCK.add(new BloodStock("B+", 24, "Green Cross Blood Centre", "Vellore"));
        STOCK.add(new BloodStock("B-", 6, "Green Cross Blood Centre", "Vellore"));
        STOCK.add(new BloodStock("AB+", 11, "City Care Blood Bank", "Gudiyatham"));
        STOCK.add(new BloodStock("AB-", 4, "Hope Blood Centre", "Katpadi"));
        STOCK.add(new BloodStock("O+", 30, "Hope Blood Centre", "Katpadi"));
        STOCK.add(new BloodStock("O-", 8, "Green Cross Blood Centre", "Vellore"));
        seedDonors();
    }

    private static void seedDonors() {
        DONORS.add(new Donor("Arun Kumar", "O+", "Gudiyatham", "", true));
        DONORS.add(new Donor("Priya S", "A+", "Vellore", "", true));
        DONORS.add(new Donor("Rahul M", "B+", "Katpadi", "", true));
    }

    // ---------- passwords and sessions ----------
    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 65536, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String hashPassword(String password) {
        byte[] salt = new byte[16]; RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt));
    }
    private static boolean checkPassword(String password, String stored) {
        if (stored == null || !stored.contains(":")) return false;
        String[] p = stored.split(":");
        return MessageDigest.isEqual(pbkdf2(password, Base64.getDecoder().decode(p[0])), Base64.getDecoder().decode(p[1]));
    }
    private static String newToken() {
        byte[] b = new byte[24]; RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    private static Map<String, String> findUser(String email) {
        for (Map<String, String> u : USERS) if (u.getOrDefault("email", "").equalsIgnoreCase(email)) return u;
        return null;
    }
    /** The signed-in user for this request, taken from the Authorization header (never from request fields). */
    private static Map<String, String> currentUser(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        String email = SESSIONS.get(h.substring(7).trim());
        return email == null ? null : findUser(email);
    }

    // ---------- validation helpers ----------
    private static String field(Map<String, String> body, String key, int maxLen) {
        String v = body.getOrDefault(key, "").trim();
        return v.length() > maxLen ? v.substring(0, maxLen) : v;
    }
    private static int intField(Map<String, String> body, String key, int fallback) {
        try { return Integer.parseInt(body.getOrDefault(key, "").trim()); } catch (Exception e) { return fallback; }
    }
    private static boolean validEmail(String e) { return e.length() <= 100 && e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"); }
    private static void fail(HttpExchange ex, int code, String message) throws IOException {
        sendJson(ex, code, json(Map.of("error", message)));
    }

    // ---------- API ----------
    private static void api(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            Map<String, String> q = parseParams(ex.getRequestURI().getRawQuery());
            Map<String, String> body = Collections.emptyMap();
            if (method.equals("POST")) {
                byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
                if (raw.length > MAX_BODY) { fail(ex, 413, "Request is too large."); return; }
                body = parseParams(new String(raw, StandardCharsets.UTF_8));
            }
            synchronized (LOCK) { route(ex, method, ex.getRequestURI().getPath(), q, body); }
        } catch (IllegalArgumentException e) {
            fail(ex, 400, "Invalid request.");
        } catch (Exception e) {
            e.printStackTrace();
            fail(ex, 500, "Server error. Please try again.");
        }
    }

    private static void route(HttpExchange ex, String method, String path, Map<String, String> q, Map<String, String> body) throws IOException {
        Map<String, String> me = currentUser(ex);
        String role = me == null ? "" : me.getOrDefault("role", "");
        boolean get = method.equals("GET"), post = method.equals("POST");

        if (get && path.equals("/api/blood")) {
            String group = q.getOrDefault("group", "").trim(), location = q.getOrDefault("location", "").trim().toLowerCase();
            List<Map<String, Object>> result = new ArrayList<>();
            for (BloodStock s : STOCK) {
                boolean groupOk = group.isEmpty() || s.group.equals(group);
                boolean locationOk = location.isEmpty() || s.location.toLowerCase().contains(location) || s.bank.toLowerCase().contains(location);
                if (groupOk && locationOk) result.add(s.map());
            }
            sendJson(ex, 200, json(result));

        } else if (get && path.equals("/api/dashboard")) {
            // Public: stock and counts. Request details (hospital contacts) are for staff only. Donor lists are never sent.
            boolean staff = role.equals("ADMIN") || role.equals("HOSPITAL");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("stockTotal", STOCK.stream().mapToInt(s -> s.units).sum());
            d.put("emergencyTotal", REQUESTS.size());
            d.put("donorTotal", DONORS.size());
            d.put("donorAvailable", DONORS.stream().filter(x -> x.available).count());
            d.put("lowStock", STOCK.stream().filter(s -> s.units < LOW_STOCK).count());
            d.put("inventory", STOCK.stream().map(BloodStock::map).toList());
            d.put("requests", staff ? REQUESTS.stream().map(EmergencyRequest::map).toList() : List.of());
            sendJson(ex, 200, json(d));

        } else if (post && path.equals("/api/login")) {
            String email = field(body, "email", 100), password = body.getOrDefault("password", "");
            if (email.isBlank() || password.isBlank()) { fail(ex, 400, "Enter email and password."); return; }
            Map<String, String> user = findUser(email);
            if (user == null || !checkPassword(password, user.get("passHash"))) {
                fail(ex, 401, "Email or password is incorrect. Create an account first or check your details."); return;
            }
            String token = newToken();
            SESSIONS.put(token, user.get("email"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true); result.put("name", user.get("name")); result.put("role", user.getOrDefault("role", "PATIENT"));
            result.put("email", user.get("email")); result.put("token", token); result.put("message", "Sign-in verified successfully.");
            sendJson(ex, 200, json(result));

        } else if (post && path.equals("/api/logout")) {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if (h != null && h.startsWith("Bearer ")) SESSIONS.remove(h.substring(7).trim());
            sendJson(ex, 200, "{\"ok\":true}");

        } else if (post && path.equals("/api/register")) {
            String name = field(body, "name", 80), email = field(body, "email", 100).toLowerCase();
            String password = body.getOrDefault("password", "");
            if (name.isBlank() || email.isBlank() || password.isBlank()) { fail(ex, 400, "Please fill in the required fields."); return; }
            if (!validEmail(email)) { fail(ex, 400, "Enter a valid email address."); return; }
            if (password.length() < 6 || password.length() > 100) { fail(ex, 400, "Password must be 6 to 100 characters."); return; }
            String requestedRole = body.getOrDefault("role", "DONOR");
            if (!Set.of("DONOR", "PATIENT", "HOSPITAL").contains(requestedRole)) { fail(ex, 400, "Choose a valid account type."); return; }
            if (findUser(email) != null) { fail(ex, 409, "An account with this email already exists. Please sign in."); return; }
            Map<String, String> user = new HashMap<>();
            user.put("name", name); user.put("email", email); user.put("phone", field(body, "phone", 30));
            user.put("location", field(body, "location", 60)); user.put("role", requestedRole); user.put("passHash", hashPassword(password));
            USERS.add(user);
            if (requestedRole.equals("DONOR")) {
                String loc = user.get("location");
                DONORS.add(new Donor(name, "Not specified", loc.isBlank() ? "Not specified" : loc, email, true));
            }
            saveData();
            sendJson(ex, 200, "{\"ok\":true,\"message\":\"Account created and saved.\"}");

        } else if (post && path.equals("/api/donor-profile")) {
            if (!role.equals("DONOR")) { fail(ex, 403, "A signed-in donor account is required."); return; }
            String group = body.getOrDefault("group", "").trim();
            if (!GROUPS.contains(group)) { fail(ex, 400, "Choose a valid blood group."); return; }
            boolean available = !"false".equals(body.getOrDefault("available", "true"));
            String email = me.get("email");
            DONORS.removeIf(d -> email.equalsIgnoreCase(d.email));
            DONORS.add(new Donor(me.get("name"), group, me.getOrDefault("location", "Not specified"), email, available));
            saveData();
            sendJson(ex, 200, "{\"ok\":true,\"message\":\"Donor profile updated.\"}");

        } else if (post && path.equals("/api/stock")) {
            if (!role.equals("ADMIN")) { fail(ex, 403, "Admin access is required."); return; }
            String group = body.getOrDefault("group", "").trim(), bank = field(body, "bank", 80), location = field(body, "location", 60);
            int units = intField(body, "units", -1);
            if (!GROUPS.contains(group) || bank.isBlank() || location.isBlank() || units < 0 || units > 1000) {
                fail(ex, 400, "Enter a valid blood group, bank, location and units (0 to 1000)."); return;
            }
            BloodStock found = null;
            for (BloodStock st : STOCK) if (st.group.equals(group) && st.bank.equalsIgnoreCase(bank) && st.location.equalsIgnoreCase(location)) { found = st; break; }
            if (found == null) STOCK.add(new BloodStock(group, units, bank, location)); else found.units = units;
            saveData();
            sendJson(ex, 200, "{\"ok\":true,\"message\":\"Blood stock updated.\"}");

        } else if (post && path.equals("/api/emergency")) {
            if (me == null) { fail(ex, 401, "Please sign in to submit an emergency request."); return; }
            String group = body.getOrDefault("group", "").trim(), hospital = field(body, "hospital", 100), location = field(body, "location", 60);
            String urgency = body.getOrDefault("urgency", "URGENT");
            int units = intField(body, "units", 0);
            if (!GROUPS.contains(group) || hospital.isBlank() || location.isBlank() || units < 1 || units > 100 || !URGENCY.contains(urgency)) {
                fail(ex, 400, "Enter a valid blood group, units (1 to 100), urgency, hospital and location."); return;
            }
            EmergencyRequest r = new EmergencyRequest(NEXT_ID.incrementAndGet(), group, units, hospital, location, urgency, field(body, "contact", 60));
            REQUESTS.add(r);
            saveData();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("message", "Demo emergency request created. No SMS or real donor alerts were sent.");
            out.put("request", r.map());
            sendJson(ex, 200, json(out));

        } else if (get && path.equals("/api/refill")) {
            // Illustrative rule: keep at least 20 units per group (30 for O+/O-, which are most in demand).
            List<Map<String, Object>> rows = new ArrayList<>();
            for (BloodStock s : STOCK) {
                int target = s.group.startsWith("O") ? 30 : 20;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bloodGroup", s.group); row.put("bank", s.bank); row.put("currentUnits", s.units);
                row.put("illustrativeTarget", target); row.put("refillUnits", Math.max(0, target - s.units));
                rows.add(row);
            }
            sendJson(ex, 200, json(rows));

        } else if (post && path.equals("/api/clear")) {
            if (!role.equals("ADMIN")) { fail(ex, 403, "Admin access is required."); return; }
            REQUESTS.clear();
            USERS.removeIf(u -> !"ADMIN".equals(u.get("role")));
            SESSIONS.values().removeIf(email -> findUser(email) == null);
            DONORS.clear(); seedDonors();
            saveData();
            sendJson(ex, 200, "{\"ok\":true,\"message\":\"Demo requests and registrations cleared.\"}");

        } else {
            fail(ex, 404, "API endpoint not found.");
        }
    }

    // ---------- static files and helpers ----------
    private static void staticFiles(HttpExchange ex) throws IOException {
        String requested = ex.getRequestURI().getPath();
        if (requested.equals("/")) requested = "/index.html";
        Path root = Paths.get("public").toAbsolutePath().normalize();
        Path file = root.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.exists(file) || Files.isDirectory(file)) { sendText(ex, 404, "Page not found."); return; }
        String name = file.toString();
        String type = name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".js") ? "application/javascript; charset=utf-8" : "text/html; charset=utf-8";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static Map<String, String> parseParams(String input) {
        Map<String, String> map = new HashMap<>();
        if (input == null || input.isEmpty()) return map;
        for (String pair : input.split("&")) {
            String[] p = pair.split("=", 2);
            map.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }
    private static void sendJson(HttpExchange ex, int status, String data) throws IOException { send(ex, status, "application/json; charset=utf-8", data); }
    private static void sendText(HttpExchange ex, int status, String data) throws IOException { send(ex, status, "text/plain; charset=utf-8", data); }
    private static void send(HttpExchange ex, int status, String type, String data) throws IOException {
        byte[] b = data.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escape((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) { StringJoiner j = new StringJoiner(",", "{", "}"); m.forEach((k, v) -> j.add(json(String.valueOf(k)) + ":" + json(v))); return j.toString(); }
        if (value instanceof Iterable<?> it) { StringJoiner j = new StringJoiner(",", "[", "]"); for (Object v : it) j.add(json(v)); return j.toString(); }
        return json(value.toString());
    }
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
