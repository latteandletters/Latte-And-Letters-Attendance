import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class App {

    static final String DB_URL;
    static final String DB_USER;
    static final String DB_PASS;
    static final String MAIL_FROM;
    static final String MAIL_PASS;
    static final String MAIL_HOST;
    static final String MAIL_FROM_NAME;
    static final int MAIL_PORT;
    static final boolean MAIL_ENABLED;
    static final String PASSWORD_SCHEME = "pbkdf2";
    static final int PASSWORD_ITERATIONS = 65536;
    static final int PASSWORD_KEY_LENGTH = 256;
    static final long OTP_VALIDITY_MS = 3L * 60L * 1000L;
    static final String QR_STATIC_VERSION = "STATIC";
    static final int SESSION_VALIDITY_HOURS = 24;
    static final int MAX_EMAIL_USAGE_COUNT = ValidationUtils.MAX_EMAIL_USAGE_COUNT;
    static final String LU_CAMPUS_NAME = "Latte & Letters Main Branch";
    static final String LU_CAMPUS_ADDRESS = "Cafe-library staff attendance zone, Brgy. Bubukal, Santa Cruz, Laguna";
    static final double LU_CAMPUS_LATITUDE = 14.2554586;
    static final double LU_CAMPUS_LONGITUDE = 121.408696216589;
    static final double LU_CAMPUS_RADIUS_METERS = 300.0;
    static final SecureRandom SECURE_RANDOM = new SecureRandom();
    static final Set<String> KNOWN_NAME_SUFFIXES = new LinkedHashSet<>(
        Arrays.asList("JR", "SR", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    );

    static Connection conn;

    static synchronized Connection ensureConnection() throws SQLException {
        try {
            if (conn != null && !conn.isClosed() && conn.isValid(2)) {
                return conn;
            }
        } catch (SQLException ignored) {
        }
        conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        return conn;
    }

    static {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            props.load(fis);
            Path localConfig = Paths.get("config.local.properties");
            if (Files.exists(localConfig)) {
                try (InputStream localFis = Files.newInputStream(localConfig)) {
                    props.load(localFis);
                }
            }
            DB_URL = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=%s&serverTimezone=%s&allowPublicKeyRetrieval=true",
                props.getProperty("db.host", "localhost"),
                props.getProperty("db.port", "3306"),
                props.getProperty("db.name", "attendance_db"),
                props.getProperty("db.ssl", "false"),
                props.getProperty("db.timezone", "UTC")
            );
            DB_USER = props.getProperty("db.user", "root");
            DB_PASS = props.getProperty("db.password", "root");
            MAIL_HOST = props.getProperty("mail.host", "smtp.gmail.com");
            MAIL_PORT = Integer.parseInt(props.getProperty("mail.port", "587"));
            MAIL_FROM = props.getProperty("mail.from", "your_gmail@gmail.com");
            MAIL_PASS = props.getProperty("mail.password", "xxxx xxxx xxxx xxxx");
            MAIL_FROM_NAME = props.getProperty("mail.from_name", "Latte & Letters");
            MAIL_ENABLED = Boolean.parseBoolean(props.getProperty("mail.enabled", "false"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.properties", e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==============================================");
        System.out.println("  Latte & Letters - Staff Attendance System  ");
        System.out.println("==============================================");
        System.out.println("[DB] Connecting to MySQL...");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            ensureConnection();
            System.out.println("[DB] Connected successfully!");
        } catch (ClassNotFoundException e) {
            System.out.println("[ERROR] MySQL JDBC driver not found in lib/");
            System.exit(1);
        } catch (SQLException e) {
            System.out.println("[ERROR] Cannot connect to MySQL: " + e.getMessage());
            System.out.println("  Fix: 1) Ensure MySQL is running on the configured host/port  2) Import schema.sql + seed.sql into the database");
            System.exit(1);
        }
        ensureDatabaseCompatibility();
        migrateLegacyPasswords();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        server.createContext("/", new StaticHandler());

        server.createContext("/api/login",          new LoginHandler());
        server.createContext("/api/register",       new RegisterHandler());
        server.createContext("/api/logout",         new LogoutHandler());
        server.createContext("/api/reset-password", new ResetPasswordHandler());
        server.createContext("/api/change-password", new ChangePasswordHandler());
        server.createContext("/api/profile",        new ProfileHandler());
        server.createContext("/api/send-otp",       new SendOtpHandler());
        server.createContext("/api/verify-otp",     new VerifyOtpHandler());
        server.createContext("/api/check-username", new UsernameCheckHandler());

        server.createContext("/api/students",   new StudentsHandler());
        server.createContext("/api/teachers",   new TeachersHandler());
        server.createContext("/api/subjects",   new SubjectsHandler());
        server.createContext("/api/attendance", new AttendanceHandler());
        server.createContext("/api/timetable",  new TimetableHandler());
        server.createContext("/api/reports",    new ReportsHandler());
        server.createContext("/api/dashboard",  new DashboardHandler());
        server.createContext("/api/qr",         new QRHandler());
        server.createContext("/api/notify",     new NotifyHandler());
        server.start();
        System.out.println("[SERVER] Running  →  http://localhost:8080");
        System.out.println("[SERVER] Press Ctrl+C to stop.");
    }

    // ── DB helpers ─────────────────────────────────────────────
    static ResultSet query(String sql, Object... p) throws SQLException {
        PreparedStatement ps = ensureConnection().prepareStatement(sql);
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
        return ps.executeQuery();
    }

    static int update(String sql, Object... p) throws SQLException {
        PreparedStatement ps = ensureConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
        ps.executeUpdate();
        ResultSet k = ps.getGeneratedKeys();
        return k.next() ? k.getInt(1) : -1;
    }

    // ── HTTP helpers ───────────────────────────────────────────
    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type",                 "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), "UTF-8");
    }

    static Map<String, String> parseJson(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        String json = raw.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replace("\"", "");
                String v = kv[1].trim().replaceAll("^\"|\"$", "");
                map.put(k, v);
            }
        }
        return map;
    }

    static String resultToJson(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        while (rs.next()) {
            if (!first) sb.append(",");
            sb.append("{");
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sb.append(",");
                sb.append("\"").append(meta.getColumnLabel(i)).append("\":");
                Object val = rs.getObject(i);
                if (val == null) sb.append("null");
                else sb.append("\"").append(val.toString().replace("\\","\\\\").replace("\"","\\\"")).append("\"");
            }
            sb.append("}");
            first = false;
        }
        return sb.append("]").toString();
    }

    // ── Token store ────────────────────────────────────────────
    static final Map<String, Map<String, String>> tokenStore = new ConcurrentHashMap<>();
    static final Map<String, Long> verifiedOtpStore = new ConcurrentHashMap<>();

    static Map<String, String> validateToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        Map<String, String> cached = tokenStore.get(token);
        if (cached != null) return cached;
        try {
            Map<String, String> restored = loadSessionUser(token);
            if (restored != null) tokenStore.put(token, restored);
            return restored;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to validate session token", e);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return map;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }

    static boolean hasRole(Map<String, String> user, String... roles) {
        if (user == null) return false;
        String role = canonicalRole(user.get("role"));
        for (String allowed : roles) {
            if (canonicalRole(allowed).equals(role)) return true;
        }
        return false;
    }

    static String canonicalRole(String role) {
        String normalized = normalize(role).toLowerCase(Locale.ROOT);
        if (normalized.equals("student")) return "staff";
        if (normalized.equals("teacher")) return "supervisor";
        return normalized;
    }

    static boolean isTruthy(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes");
    }

    static String currentDate() {
        return new java.sql.Date(System.currentTimeMillis()).toString();
    }

    static String currentTime() {
        return new java.sql.Time(System.currentTimeMillis()).toString();
    }

    static long currentEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    static String qrSignatureMessage(String studentProfileId, String studentNumber, String versionOrIssuedAt) {
        return studentProfileId + "|" + studentNumber + "|" + versionOrIssuedAt;
    }

    static String buildQrSignature(String studentProfileId, String studentNumber, String versionOrIssuedAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] secret = (DB_URL + "|" + DB_USER + "|" + DB_PASS + "|LATTELETTERS_QR").getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(qrSignatureMessage(studentProfileId, studentNumber, versionOrIssuedAt).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Unable to sign QR payload", e);
        }
    }

    static String buildQrSignature(String studentProfileId, String studentNumber, long issuedAt) {
        return buildQrSignature(studentProfileId, studentNumber, String.valueOf(issuedAt));
    }

    static String buildStudentQrPayload(Map<String, String> student) {
        return buildStudentQrPayload(student, QR_STATIC_VERSION);
    }

    static String buildStudentQrPayload(Map<String, String> student, long issuedAt) {
        return buildStudentQrPayload(student, String.valueOf(issuedAt));
    }

    static String buildStudentQrPayload(Map<String, String> student, String versionOrIssuedAt) {
        String studentProfileId = normalize(student.get("id"));
        String studentNumber = normalize(student.get("student_id"));
        String version = normalize(versionOrIssuedAt);
        String signature = buildQrSignature(studentProfileId, studentNumber, version);
        return "LATTELETTERS|STAFF|" + studentProfileId + "|" + studentNumber + "|" + version + "|" + signature;
    }

    static Map<String, String> parseStudentQrPayload(String qrData) throws SQLException {
        String normalized = normalize(qrData);
        String[] parts = normalized.split("\\|");
        require(parts.length >= 6, "Invalid QR code. Please scan a valid staff QR code.");
        String systemKey = normalize(parts[0]);
        String qrRole = normalize(parts[1]);
        boolean isSupportedQr = "LATTELETTERS".equalsIgnoreCase(systemKey) && "STAFF".equalsIgnoreCase(qrRole);
        require(isSupportedQr, "Invalid QR code. Please scan a valid staff QR code.");

        String studentProfileId = normalize(parts[2]);
        String studentNumber = normalize(parts[3]);
        String versionOrIssuedAt = normalize(parts[4]);
        String signature = normalize(parts[5]);
        require(isPositiveInteger(studentProfileId), "Invalid QR code. Please scan a valid staff QR code.");
        require(!studentNumber.isEmpty() && !versionOrIssuedAt.isEmpty() && !signature.isEmpty(),
            "Invalid QR code. Please scan a valid staff QR code.");

        require(buildQrSignature(studentProfileId, studentNumber, versionOrIssuedAt).equals(signature),
            "Invalid QR code. Please scan a valid staff QR code.");

        Map<String, String> student = resolveStudent(studentProfileId);
        require(student != null, "Staff not found for this QR code");
        require(studentNumber.equals(student.get("student_id")), "Invalid QR code. Please scan a valid staff QR code.");
        student.put("qr_issued_at", versionOrIssuedAt);
        return student;
    }

    static boolean hasQrAttendanceForDate(String studentId, String date) throws SQLException {
        return recordExists(
            "SELECT id FROM attendance WHERE student_id=? AND date=? AND method='qr' LIMIT 1",
            studentId, date
        );
    }

    static String[] resolveDashboardDateRange(Map<String, String> qs) {
        LocalDate today = LocalDate.now();
        String defaultFrom = today.withDayOfMonth(1).toString();
        String defaultTo = today.toString();

        String from = normalize(qs.get("from"));
        String to = normalize(qs.get("to"));

        if (from.isEmpty()) from = defaultFrom;
        if (to.isEmpty()) to = defaultTo;

        require(isValidDate(from), "From date must use YYYY-MM-DD format");
        require(isValidDate(to), "To date must use YYYY-MM-DD format");
        require(!LocalDate.parse(from).isAfter(LocalDate.parse(to)), "From date must not be later than To date");
        return new String[] { from, to };
    }

    static void persistSession(String userId, String token) throws SQLException {
        update("DELETE FROM sessions WHERE user_id=? OR expires_at <= NOW()", userId);
        update("INSERT INTO sessions(user_id,token,expires_at) VALUES(?,?,DATE_ADD(NOW(), INTERVAL " + SESSION_VALIDITY_HOURS + " HOUR))",
            userId, token);
    }

    static void clearSession(String token) throws SQLException {
        tokenStore.remove(token);
        update("DELETE FROM sessions WHERE token=?", token);
    }

    static Map<String, String> loadSessionUser(String token) throws SQLException {
        ResultSet rs = query(
            "SELECT u.id, u.username, u.role," +
            " COALESCE(" + nullableFullNameSql("t") + ", " + nullableFullNameSql("s") + ", u.username) AS full_name," +
            " s.id AS student_profile_id, t.id AS teacher_profile_id, s.student_id AS student_number" +
            " FROM sessions se" +
            " JOIN users u ON u.id=se.user_id" +
            " LEFT JOIN teachers t ON t.user_id=u.id AND COALESCE(t.is_archived,FALSE)=FALSE" +
            " LEFT JOIN students s ON s.user_id=u.id AND COALESCE(s.is_archived,FALSE)=FALSE" +
            " WHERE se.token=? AND se.expires_at > NOW() AND COALESCE(u.is_archived,FALSE)=FALSE LIMIT 1",
            token
        );
        if (!rs.next()) {
            update("DELETE FROM sessions WHERE token=? AND expires_at <= NOW()", token);
            return null;
        }
        String role = canonicalRole(rs.getString("role"));
        if (("staff".equals(role) && isBlank(rs.getString("student_profile_id"))) ||
            ("supervisor".equals(role) && isBlank(rs.getString("teacher_profile_id")))) {
            clearSession(token);
            return null;
        }

        Map<String, String> user = new HashMap<>();
        user.put("id", rs.getString("id"));
        user.put("username", rs.getString("username"));
        user.put("role", role);
        user.put("full_name", rs.getString("full_name"));
        String profileId = role.equals("staff")
            ? rs.getString("student_profile_id")
            : role.equals("supervisor")
                ? rs.getString("teacher_profile_id")
                : rs.getString("id");
        user.put("profile_id", profileId == null ? "" : profileId);
        user.put("student_number", rs.getString("student_number") == null ? "" : rs.getString("student_number"));
        return user;
    }

    static String normalize(String value) { return ValidationUtils.normalize(value); }

    static String normalizeEmail(String value) { return ValidationUtils.normalizeEmail(value); }

    static String normalizeCourse(String value) { return ValidationUtils.normalizeCourse(value); }

    static String normalizeSection(String value) { return ValidationUtils.normalizeSection(value); }

    static String normalizeSubjectCode(String value) { return ValidationUtils.normalizeSubjectCode(value); }

    static String normalizeAddressComponent(String value) { return ValidationUtils.normalizeAddressComponent(value); }

    static boolean isBlank(String value) { return ValidationUtils.isBlank(value); }

    static String toNameCase(String value) { return ValidationUtils.toNameCase(value); }

    static class NameParts {
        final String firstName;
        final String middleName;
        final String lastName;
        final String suffix;
        final String fullName;

        NameParts(String firstName, String middleName, String lastName, String suffix) {
            this.firstName = normalizeNamePart(firstName);
            this.middleName = normalizeNamePart(middleName);
            this.lastName = normalizeNamePart(lastName);
            this.suffix = normalizeSuffix(suffix);
            this.fullName = buildFullName(this.firstName, this.middleName, this.lastName, this.suffix);
        }
    }

    static String normalizeNamePart(String value) {
        return toNameCase(normalize(value));
    }

    static String normalizeSuffix(String value) {
        String cleaned = normalize(value).replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return "";
        String upper = cleaned.replace(".", "").toUpperCase(Locale.ROOT);
        if (upper.equals("JR")) return "Jr.";
        if (upper.equals("SR")) return "Sr.";
        if (KNOWN_NAME_SUFFIXES.contains(upper)) return upper;
        return toNameCase(cleaned);
    }

    static String buildFullName(String firstName, String middleName, String lastName, String suffix) {
        return Stream.of(firstName, middleName, lastName, suffix)
            .map(App::normalize)
            .filter(part -> !part.isEmpty())
            .collect(Collectors.joining(" "));
    }

    static NameParts parseStoredFullName(String fullName) {
        List<String> tokens = Stream.of(normalize(fullName).split("\\s+"))
            .filter(token -> !token.isEmpty())
            .collect(Collectors.toCollection(ArrayList::new));
        if (tokens.isEmpty()) return new NameParts("", "", "", "");
        String suffix = "";
        if (!tokens.isEmpty()) {
            String possibleSuffix = tokens.get(tokens.size() - 1).replace(".", "").toUpperCase(Locale.ROOT);
            if (KNOWN_NAME_SUFFIXES.contains(possibleSuffix)) {
                suffix = tokens.remove(tokens.size() - 1);
            }
        }
        if (tokens.isEmpty()) return new NameParts("", "", "", suffix);
        if (tokens.size() == 1) return new NameParts(tokens.get(0), "", "", suffix);
        String firstName = tokens.remove(0);
        String lastName;
        if (tokens.size() >= 3) {
            String connectorPair = (tokens.get(tokens.size() - 3) + " " + tokens.get(tokens.size() - 2)).toLowerCase(Locale.ROOT);
            if (connectorPair.equals("de la") || connectorPair.equals("de los") || connectorPair.equals("de las")) {
                lastName = tokens.remove(tokens.size() - 3) + " " + tokens.remove(tokens.size() - 2) + " " + tokens.remove(tokens.size() - 1);
                return new NameParts(firstName, String.join(" ", tokens), lastName, suffix);
            }
        }
        if (tokens.size() >= 2) {
            String connector = tokens.get(tokens.size() - 2).toLowerCase(Locale.ROOT);
            if (Arrays.asList("de", "del", "dela", "delos", "la", "las", "los", "san", "santa", "sta", "st", "van", "von").contains(connector)) {
                lastName = tokens.remove(tokens.size() - 2) + " " + tokens.remove(tokens.size() - 1);
                return new NameParts(firstName, String.join(" ", tokens), lastName, suffix);
            }
        }
        lastName = tokens.remove(tokens.size() - 1);
        return new NameParts(firstName, String.join(" ", tokens), lastName, suffix);
    }

    static NameParts resolveNameParts(Map<String, String> payload, String fallbackFullName) {
        String firstName = normalizeNamePart(payload.getOrDefault("first_name", ""));
        String middleName = normalizeNamePart(payload.getOrDefault("middle_name", ""));
        String lastName = normalizeNamePart(payload.getOrDefault("last_name", ""));
        String suffix = normalizeSuffix(payload.getOrDefault("suffix", ""));
        if (!firstName.isEmpty() || !middleName.isEmpty() || !lastName.isEmpty() || !suffix.isEmpty()) {
            return new NameParts(firstName, middleName, lastName, suffix);
        }
        return parseStoredFullName(fallbackFullName);
    }

    static String displayFullName(ResultSet rs) throws SQLException {
        NameParts fromColumns = new NameParts(
            rs.getString("first_name"),
            rs.getString("middle_name"),
            rs.getString("last_name"),
            rs.getString("suffix")
        );
        if (!fromColumns.fullName.isEmpty()) return fromColumns.fullName;
        String aliasedFullName = getOptionalColumn(rs, "full_name");
        return toNameCase(aliasedFullName);
    }

    static String getOptionalColumn(ResultSet rs, String columnLabel) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (columnLabel.equalsIgnoreCase(meta.getColumnLabel(i))) {
                return rs.getString(columnLabel);
            }
        }
        return null;
    }

    static String qualified(String alias, String columnName) {
        return isBlank(alias) ? columnName : alias + "." + columnName;
    }

    static String computedFullNameSql(String alias) {
        return "TRIM(CONCAT_WS(' ', " +
            "NULLIF(" + qualified(alias, "first_name") + ", ''), " +
            "NULLIF(" + qualified(alias, "middle_name") + ", ''), " +
            "NULLIF(" + qualified(alias, "last_name") + ", ''), " +
            "NULLIF(" + qualified(alias, "suffix") + ", '')))";
    }

    static String nullableFullNameSql(String alias) {
        return "NULLIF(" + computedFullNameSql(alias) + ", '')";
    }

    static String computedFullNameAliasSql(String alias) {
        return computedFullNameSql(alias) + " AS full_name";
    }

    static String fullNameOrderSql(String alias) {
        return "COALESCE(" + qualified(alias, "last_name") + ", '')," +
            " COALESCE(" + qualified(alias, "first_name") + ", '')," +
            " COALESCE(" + qualified(alias, "middle_name") + ", '')," +
            " COALESCE(" + qualified(alias, "suffix") + ", '')";
    }

    static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    static void require(boolean condition, String message) { ValidationUtils.require(condition, message); }

    static String getEmailValidationError(String email) { return ValidationUtils.getEmailValidationError(email); }

    static boolean isValidEmail(String email) { return ValidationUtils.isValidEmail(email); }

    static void requireValidEmail(String email) {
        String error = getEmailValidationError(email);
        if (error != null) throw new IllegalArgumentException(error);
    }

    static boolean isValidUsername(String username) { return ValidationUtils.isValidUsername(username); }

    static boolean isValidFullName(String fullName) { return ValidationUtils.isValidFullName(fullName); }

    static boolean isValidPhone(String phone) { return ValidationUtils.isValidPhone(phone); }

    static boolean isValidCourse(String course) { return ValidationUtils.isValidCourse(course); }

    static boolean isValidSection(String section) { return ValidationUtils.isValidSection(section); }

    static boolean isValidYearLevel(String yearLevel) { return ValidationUtils.isValidYearLevel(yearLevel); }

    static boolean isValidDate(String date) { return ValidationUtils.isValidDate(date); }

    static String getBirthDateValidationError(String birthDate) { return ValidationUtils.getBirthDateValidationError(birthDate); }

    static boolean isValidBirthDate(String birthDate) { return ValidationUtils.isValidBirthDate(birthDate); }

    static int calculateAgeFromBirthDate(String birthDate) { return ValidationUtils.calculateAgeFromBirthDate(birthDate); }

    static boolean isLagunaProvince(String province) { return ValidationUtils.isLagunaProvince(province); }

    static String getAddressComponentError(String label, String value, int maxLength) {
        return ValidationUtils.getAddressComponentError(label, value, maxLength);
    }

    static boolean isPositiveInteger(String value) { return ValidationUtils.isPositiveInteger(value); }

    static boolean isValidStudentNumber(String studentNumber) { return ValidationUtils.isValidStudentNumber(studentNumber); }

    static boolean isValidSubjectCode(String subjectCode) { return ValidationUtils.isValidSubjectCode(subjectCode); }

    static boolean isValidSubjectName(String subjectName) { return ValidationUtils.isValidSubjectName(subjectName); }

    static boolean isValidOtpPurpose(String purpose) { return ValidationUtils.isValidOtpPurpose(purpose); }

    static boolean isAllowedSelfRegisterRole(String role) { return ValidationUtils.isAllowedSelfRegisterRole(role); }

    static boolean isAllowedAttendanceStatus(String status) { return ValidationUtils.isAllowedAttendanceStatus(status); }

    static boolean isAllowedAttendanceMethod(String method) { return ValidationUtils.isAllowedAttendanceMethod(method); }


    static int parseUnits(String value) { return ValidationUtils.parseUnits(value); }

    static boolean recordExists(String sql, Object... params) throws SQLException { return ValidationUtils.recordExists(sql, params); }

    static boolean usernameExists(String username, String exceptUserId) throws SQLException {
        return ValidationUtils.usernameExists(username, exceptUserId);
    }

    static boolean emailExists(String email, String exceptUserId) throws SQLException {
        return ValidationUtils.emailExists(email, exceptUserId);
    }

    static int getEmailUsageCount(String email, String exceptUserId) throws SQLException {
        return ValidationUtils.getEmailUsageCount(email, exceptUserId);
    }

    static boolean hasReachedEmailUsageLimit(String email, String exceptUserId) throws SQLException {
        return ValidationUtils.hasReachedEmailUsageLimit(email, exceptUserId);
    }

    static String getEmailUsageLimitMessage() {
        return MAX_EMAIL_USAGE_COUNT <= 1
            ? "Email is already registered"
            : "This email can only be used for up to " + MAX_EMAIL_USAGE_COUNT + " accounts";
    }

    static void ensureDatabaseCompatibility() throws SQLException {
        migrateUserRoleValues();
        ensureColumnExists("users", "is_archived", "BOOLEAN NOT NULL DEFAULT FALSE AFTER role");
        ensureColumnExists("users", "archived_at", "TIMESTAMP NULL DEFAULT NULL AFTER is_archived");
        ensureColumnExists("notifications", "details", "TEXT NULL AFTER message");
        ensureColumnExists("students", "is_archived", "BOOLEAN NOT NULL DEFAULT FALSE AFTER qr_code");
        ensureColumnExists("students", "archived_at", "TIMESTAMP NULL DEFAULT NULL AFTER is_archived");
        ensureColumnExists("students", "first_name", "VARCHAR(80) NULL AFTER student_id");
        ensureColumnExists("students", "middle_name", "VARCHAR(80) NULL AFTER first_name");
        ensureColumnExists("students", "last_name", "VARCHAR(80) NULL AFTER middle_name");
        ensureColumnExists("students", "suffix", "VARCHAR(20) NULL AFTER last_name");
        ensureColumnExists("students", "date_of_birth", "DATE NULL AFTER phone");
        ensureColumnExists("students", "age", "INT NULL AFTER date_of_birth");
        ensureColumnExists("students", "province", "VARCHAR(60) NULL AFTER age");
        ensureColumnExists("students", "municipality", "VARCHAR(80) NULL AFTER province");
        ensureColumnExists("students", "barangay", "VARCHAR(100) NULL AFTER municipality");
        ensureColumnExists("students", "college", "VARCHAR(120) NULL AFTER barangay");
        ensureColumnExists("students", "specialization", "VARCHAR(80) NULL AFTER course");
        ensureColumnDefinition("students", "course", "VARCHAR(120) NULL");
        ensureColumnDefinition("students", "college", "VARCHAR(120) NULL");
        ensureColumnDefinition("students", "specialization", "VARCHAR(120) NULL");
        ensureColumnExists("teachers", "is_archived", "BOOLEAN NOT NULL DEFAULT FALSE AFTER profile_picture");
        ensureColumnExists("teachers", "archived_at", "TIMESTAMP NULL DEFAULT NULL AFTER is_archived");
        ensureColumnExists("teachers", "first_name", "VARCHAR(80) NULL AFTER user_id");
        ensureColumnExists("teachers", "middle_name", "VARCHAR(80) NULL AFTER first_name");
        ensureColumnExists("teachers", "last_name", "VARCHAR(80) NULL AFTER middle_name");
        ensureColumnExists("teachers", "suffix", "VARCHAR(20) NULL AFTER last_name");
        ensureColumnExists("teachers", "date_of_birth", "DATE NULL AFTER phone");
        ensureColumnExists("teachers", "age", "INT NULL AFTER date_of_birth");
        ensureColumnExists("teachers", "province", "VARCHAR(60) NULL AFTER age");
        ensureColumnExists("teachers", "municipality", "VARCHAR(80) NULL AFTER province");
        ensureColumnExists("teachers", "barangay", "VARCHAR(100) NULL AFTER municipality");
        ensureColumnExists("teachers", "specialization", "VARCHAR(120) NULL AFTER subject");
        ensureColumnDefinition("teachers", "user_id", "INT NULL");
        ensureColumnDefinition("teachers", "department", "VARCHAR(120) NULL");
        ensureColumnDefinition("teachers", "subject", "VARCHAR(120) NULL");
        ensureColumnDefinition("teachers", "specialization", "VARCHAR(120) NULL");
        ensureColumnExists("subjects", "is_archived", "BOOLEAN NOT NULL DEFAULT FALSE AFTER units");
        ensureColumnExists("subjects", "archived_at", "TIMESTAMP NULL DEFAULT NULL AFTER is_archived");
        ensureColumnExists("subjects", "college", "VARCHAR(80) NULL AFTER teacher_id");
        ensureColumnExists("subjects", "course", "VARCHAR(30) NULL AFTER name");
        ensureColumnExists("subjects", "specialization", "VARCHAR(80) NULL AFTER course");
        ensureColumnDefinition("subjects", "college", "VARCHAR(120) NULL");
        ensureColumnDefinition("subjects", "course", "VARCHAR(120) NULL");
        ensureColumnDefinition("subjects", "specialization", "VARCHAR(120) NULL");
        ensureColumnExists("subjects", "year_level", "INT NULL AFTER course");
        ensureColumnExists("subjects", "section", "VARCHAR(20) NULL AFTER year_level");
        ensureColumnDefinition("students", "section", "VARCHAR(40) NULL");
        ensureColumnDefinition("subjects", "section", "VARCHAR(40) NULL");
        ensureColumnExists("attendance", "location_address", "TEXT NULL AFTER longitude");
        ensureSessionsTable();
        backfillSeedSubjectTargets();
        backfillSplitNameColumns();
        dropColumnIfExists("students", "full_name");
        dropColumnIfExists("teachers", "full_name");
    }

    static void migrateUserRoleValues() throws SQLException {
        ensureColumnDefinition("users", "role", "ENUM('admin','teacher','student','supervisor','staff') NOT NULL");
        update("UPDATE users SET role='supervisor' WHERE role='teacher'");
        update("UPDATE users SET role='staff' WHERE role='student'");
        ensureColumnDefinition("users", "role", "ENUM('admin','supervisor','staff') NOT NULL");
    }

    static void ensureSessionsTable() throws SQLException {
        try (Statement stmt = ensureConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "user_id INT NOT NULL," +
                "token VARCHAR(50) UNIQUE NOT NULL," +
                "expires_at TIMESTAMP NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)"
            );
        }
    }

    static void backfillSeedSubjectTargets() throws SQLException {
        update(
            "UPDATE subjects SET college='Main Branch', course='Coffee Bar', specialization='Barista', year_level=1, section='Opening (6am - 2pm)' " +
            "WHERE code IN ('BAR-01','LIB-01','INV-01') " +
            "AND (course IS NULL OR year_level IS NULL OR section IS NULL)"
        );
    }

    static void backfillSplitNameColumns() throws SQLException {
        backfillSplitNameColumns("students");
        backfillSplitNameColumns("teachers");
    }

    static void backfillSplitNameColumns(String tableName) throws SQLException {
        if (!columnExists(tableName, "full_name")) return;
        ResultSet rs = query(
            "SELECT id, full_name, first_name, middle_name, last_name, suffix FROM " + tableName
        );
        while (rs.next()) {
            NameParts resolved = new NameParts(
                rs.getString("first_name"),
                rs.getString("middle_name"),
                rs.getString("last_name"),
                rs.getString("suffix")
            );
            if (resolved.fullName.isEmpty()) {
                resolved = parseStoredFullName(rs.getString("full_name"));
            }
            if (resolved.fullName.isEmpty()) continue;
            String currentFullName = toNameCase(rs.getString("full_name"));
            boolean needsUpdate =
                !Objects.equals(normalize(rs.getString("first_name")), normalize(resolved.firstName)) ||
                !Objects.equals(normalize(rs.getString("middle_name")), normalize(resolved.middleName)) ||
                !Objects.equals(normalize(rs.getString("last_name")), normalize(resolved.lastName)) ||
                !Objects.equals(normalize(rs.getString("suffix")), normalize(resolved.suffix)) ||
                !Objects.equals(normalize(currentFullName), normalize(resolved.fullName));
            if (!needsUpdate) continue;
            update(
                "UPDATE " + tableName + " SET first_name=?, middle_name=?, last_name=?, suffix=?, full_name=? WHERE id=?",
                blankToNull(resolved.firstName),
                blankToNull(resolved.middleName),
                blankToNull(resolved.lastName),
                blankToNull(resolved.suffix),
                resolved.fullName,
                rs.getString("id")
            );
        }
    }

    static boolean columnExists(String tableName, String columnName) throws SQLException {
        Connection db = ensureConnection();
        DatabaseMetaData metaData = db.getMetaData();
        try (ResultSet rs = metaData.getColumns(db.getCatalog(), null, tableName, columnName)) {
            return rs.next();
        }
    }

    static void ensureColumnExists(String tableName, String columnName, String columnDefinition) throws SQLException {
        Connection db = ensureConnection();
        DatabaseMetaData metaData = db.getMetaData();
        try (ResultSet rs = metaData.getColumns(db.getCatalog(), null, tableName, columnName)) {
            if (rs.next()) return;
        }

        try (Statement stmt = db.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            System.out.println("[DB] Added missing column " + tableName + "." + columnName);
        }
    }

    static void ensureColumnDefinition(String tableName, String columnName, String columnDefinition) throws SQLException {
        if (!columnExists(tableName, columnName)) return;
        try (Statement stmt = ensureConnection().createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + columnDefinition);
        }
    }

    static void dropColumnIfExists(String tableName, String columnName) throws SQLException {
        if (!columnExists(tableName, columnName)) return;
        try (Statement stmt = ensureConnection().createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " DROP COLUMN " + columnName);
            System.out.println("[DB] Dropped legacy column " + tableName + "." + columnName);
        }
    }

    static String hashPassword(String rawPassword) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derivePasswordHash(rawPassword.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
        return PASSWORD_SCHEME + "$" + PASSWORD_ITERATIONS + "$" +
            Base64.getEncoder().encodeToString(salt) + "$" +
            Base64.getEncoder().encodeToString(hash);
    }

    static byte[] derivePasswordHash(char[] rawPassword, byte[] salt, int iterations, int keyLength) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(rawPassword, salt, iterations, keyLength);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static boolean isHashedPassword(String storedPassword) {
        return storedPassword != null && storedPassword.startsWith(PASSWORD_SCHEME + "$");
    }

    static boolean verifyPassword(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) return false;
        if (!isHashedPassword(storedPassword)) return storedPassword.equals(rawPassword);
        try {
            String[] parts = storedPassword.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derivePasswordHash(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    static void migrateLegacyPasswords() throws Exception {
        ResultSet rs = query("SELECT id, password FROM users");
        List<String[]> toUpgrade = new ArrayList<>();
        while (rs.next()) {
            String storedPassword = rs.getString("password");
            if (!isBlank(storedPassword) && !isHashedPassword(storedPassword)) {
                toUpgrade.add(new String[] { rs.getString("id"), storedPassword });
            }
        }
        for (String[] item : toUpgrade) {
            update("UPDATE users SET password=? WHERE id=?", hashPassword(item[1]), item[0]);
        }
        if (!toUpgrade.isEmpty()) {
            System.out.println("[AUTH] Migrated " + toUpgrade.size() + " legacy plaintext password(s) to hashed storage.");
        }
    }

    static void upgradeLegacyPasswordIfNeeded(String userId, String rawPassword, String storedPassword) throws Exception {
        if (!isHashedPassword(storedPassword)) {
            update("UPDATE users SET password=? WHERE id=?", hashPassword(rawPassword), userId);
        }
    }

    static String otpVerificationKey(String email, String purpose) {
        return normalize(purpose).toLowerCase(Locale.ROOT) + ":" + normalizeEmail(email);
    }

    static void rememberVerifiedOtp(String email, String purpose) {
        verifiedOtpStore.put(otpVerificationKey(email, purpose), System.currentTimeMillis() + OTP_VALIDITY_MS);
    }

    static void clearVerifiedOtp(String email, String purpose) {
        verifiedOtpStore.remove(otpVerificationKey(email, purpose));
    }

    static boolean consumeVerifiedOtp(String email, String purpose) {
        String key = otpVerificationKey(email, purpose);
        Long expiresAt = verifiedOtpStore.get(key);
        if (expiresAt == null) return false;
        if (expiresAt < System.currentTimeMillis()) {
            verifiedOtpStore.remove(key);
            return false;
        }
        verifiedOtpStore.remove(key);
        return true;
    }

    static int getActiveOtpRemainingSeconds(String email, String purpose) throws SQLException {
        ResultSet rs = query(
            "SELECT TIMESTAMPDIFF(SECOND, NOW(), expires_at) AS seconds_left" +
            " FROM otp_verifications" +
            " WHERE LOWER(email)=? AND purpose=? AND used=FALSE AND expires_at > NOW()" +
            " ORDER BY expires_at DESC LIMIT 1",
            normalizeEmail(email), normalize(purpose).toLowerCase(Locale.ROOT)
        );
        if (!rs.next()) return 0;
        return Math.max(0, rs.getInt("seconds_left"));
    }


    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static boolean isWithinLagunaUniversityCampus(String latitude, String longitude) {
        if (isBlank(latitude) || isBlank(longitude)) return false;
        try {
            return distanceMeters(
                Double.parseDouble(latitude),
                Double.parseDouble(longitude),
                LU_CAMPUS_LATITUDE,
                LU_CAMPUS_LONGITUDE
            ) <= LU_CAMPUS_RADIUS_METERS;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static Map<String, String> resolveStudent(String ref) throws SQLException {
        return resolveStudent(ref, false);
    }

    static Map<String, String> resolveStudent(String ref, boolean includeArchived) throws SQLException {
        if (ref == null || ref.trim().isEmpty()) return null;
        ResultSet rs = query(
            "SELECT id,user_id,student_id,first_name,middle_name,last_name,suffix,email,college,course,specialization,year_level,section,is_archived," +
            computedFullNameAliasSql("") + " FROM students" +
            " WHERE (CAST(id AS CHAR)=? OR student_id=?)" +
            (includeArchived ? "" : " AND COALESCE(is_archived,FALSE)=FALSE") +
            " LIMIT 1",
            ref, ref
        );
        if (!rs.next()) return null;
        Map<String, String> student = new LinkedHashMap<>();
        student.put("id", rs.getString("id"));
        student.put("user_id", rs.getString("user_id"));
        student.put("student_id", rs.getString("student_id"));
        student.put("full_name", displayFullName(rs));
        student.put("first_name", normalizeNamePart(rs.getString("first_name")));
        student.put("middle_name", normalizeNamePart(rs.getString("middle_name")));
        student.put("last_name", normalizeNamePart(rs.getString("last_name")));
        student.put("suffix", normalizeSuffix(rs.getString("suffix")));
        student.put("email", rs.getString("email"));
        student.put("college", rs.getString("college"));
        student.put("course", rs.getString("course"));
        student.put("specialization", rs.getString("specialization"));
        student.put("year_level", rs.getString("year_level"));
        student.put("section", rs.getString("section"));
        student.put("is_archived", rs.getString("is_archived"));
        return student;
    }

    static Map<String, String> resolveTeacher(String ref) throws SQLException {
        return resolveTeacher(ref, false);
    }

    static Map<String, String> resolveTeacher(String ref, boolean includeArchived) throws SQLException {
        if (ref == null || ref.trim().isEmpty()) return null;
        ResultSet rs = query(
            "SELECT id,user_id,first_name,middle_name,last_name,suffix,email,department,subject,is_archived," +
            computedFullNameAliasSql("") + " FROM teachers" +
            " WHERE (CAST(id AS CHAR)=? OR CAST(user_id AS CHAR)=?)" +
            (includeArchived ? "" : " AND COALESCE(is_archived,FALSE)=FALSE") +
            " LIMIT 1",
            ref, ref
        );
        if (!rs.next()) return null;
        Map<String, String> teacher = new LinkedHashMap<>();
        teacher.put("id", rs.getString("id"));
        teacher.put("user_id", rs.getString("user_id"));
        teacher.put("full_name", displayFullName(rs));
        teacher.put("first_name", normalizeNamePart(rs.getString("first_name")));
        teacher.put("middle_name", normalizeNamePart(rs.getString("middle_name")));
        teacher.put("last_name", normalizeNamePart(rs.getString("last_name")));
        teacher.put("suffix", normalizeSuffix(rs.getString("suffix")));
        teacher.put("email", rs.getString("email"));
        teacher.put("department", rs.getString("department"));
        teacher.put("subject", rs.getString("subject"));
        teacher.put("is_archived", rs.getString("is_archived"));
        return teacher;
    }

    static String studentIdPrefix(String yearLevel) {
        switch (yearLevel) {
            case "4": return "221-";
            case "3": return "231-";
            case "2": return "241-";
            case "1":
            default:  return "251-";
        }
    }

    static String generateStudentId(String yearLevel) throws SQLException {
        String prefix = studentIdPrefix(yearLevel);
        Random random = new Random();
        for (int attempt = 0; attempt < 500; attempt++) {
            String sid = prefix + String.format("%04d", random.nextInt(10000));
            ResultSet exists = query("SELECT id FROM students WHERE student_id=? LIMIT 1", sid);
            if (!exists.next()) return sid;
        }
        throw new SQLException("Unable to generate a unique staff ID");
    }

    static String resolveStudentIdForUser(Map<String, String> user, String requestedId) throws SQLException {
        if (hasRole(user, "staff")) return user.get("profile_id");
        if (requestedId == null || requestedId.trim().isEmpty()) return null;
        Map<String, String> student = resolveStudent(requestedId);
        return student == null ? null : student.get("id");
    }

    static boolean subjectMatchesStudent(Map<String, String> subject, Map<String, String> student) {
        if (subject == null || student == null) return false;
        String subjectCourse = normalizeCourse(subject.get("course"));
        String subjectYear = normalize(subject.get("year_level"));
        String subjectSection = normalizeSection(subject.get("section"));
        if (subjectCourse.isEmpty() || subjectYear.isEmpty() || subjectSection.isEmpty()) {
            return true;
        }
        return subjectCourse.equals(normalizeCourse(student.get("course")))
            && subjectYear.equals(normalize(student.get("year_level")))
            && subjectSection.equals(normalizeSection(student.get("section")));
    }

    static String workAreaMatchKey(String value) {
        return normalizeCourse(value)
            .replaceAll("\\([^)]*\\)", "")
            .replaceAll("[^A-Za-z0-9]", "")
            .toUpperCase(Locale.ROOT);
    }

    static boolean subjectMatchesStudentWorkArea(Map<String, String> subject, Map<String, String> student) {
        if (subject == null || student == null) return false;
        String studentCourse = normalizeCourse(student.get("course"));
        String subjectCourse = normalizeCourse(subject.get("course"));
        String subjectName = normalizeCourse(subject.get("name"));
        String studentKey = workAreaMatchKey(studentCourse);
        return !studentKey.isEmpty()
            && (studentKey.equals(workAreaMatchKey(subjectCourse)) || studentKey.equals(workAreaMatchKey(subjectName)));
    }

    static Map<String, String> resolveSubject(String ref) throws SQLException {
        return resolveSubject(ref, false);
    }

    static Map<String, String> resolveSubject(String ref, boolean includeArchived) throws SQLException {
        if (isBlank(ref)) return null;
        ResultSet rs = query(
            "SELECT s.*, " + computedFullNameSql("t") + " AS teacher_name FROM subjects s LEFT JOIN teachers t ON t.id=s.teacher_id" +
            " WHERE (CAST(s.id AS CHAR)=? OR UPPER(s.code)=UPPER(?))" +
            (includeArchived ? "" : " AND COALESCE(s.is_archived,FALSE)=FALSE") +
            " LIMIT 1",
            ref, ref
        );
        if (!rs.next()) return null;
        Map<String, String> subject = new LinkedHashMap<>();
        subject.put("id", rs.getString("id"));
        subject.put("code", rs.getString("code"));
        subject.put("name", rs.getString("name"));
        subject.put("teacher_id", rs.getString("teacher_id"));
        subject.put("teacher_name", rs.getString("teacher_name"));
        subject.put("units", rs.getString("units"));
        subject.put("college", rs.getString("college"));
        subject.put("course", rs.getString("course"));
        subject.put("specialization", rs.getString("specialization"));
        subject.put("year_level", rs.getString("year_level"));
        subject.put("section", rs.getString("section"));
        subject.put("is_archived", rs.getString("is_archived"));
        return subject;
    }

    static String findLinkedUserId(String tableName, String entityId) throws SQLException {
        ResultSet rs = query("SELECT user_id FROM " + tableName + " WHERE id=? LIMIT 1", entityId);
        return rs.next() ? rs.getString("user_id") : null;
    }

    static void archiveRecord(String tableName, String entityId) throws SQLException {
        update("UPDATE " + tableName + " SET is_archived=TRUE, archived_at=NOW() WHERE id=?", entityId);
    }

    static void restoreRecord(String tableName, String entityId) throws SQLException {
        update("UPDATE " + tableName + " SET is_archived=FALSE, archived_at=NULL WHERE id=?", entityId);
    }

    static void archiveUserAccount(String userId) throws SQLException {
        if (isBlank(userId)) return;
        update("UPDATE users SET is_archived=TRUE, archived_at=NOW() WHERE id=?", userId);
        update("DELETE FROM sessions WHERE user_id=?", userId);
    }

    static void restoreUserAccount(String userId) throws SQLException {
        if (isBlank(userId)) return;
        update("UPDATE users SET is_archived=FALSE, archived_at=NULL WHERE id=?", userId);
    }

    static void deleteUserAccount(String userId) throws SQLException {
        if (isBlank(userId)) return;
        update("DELETE FROM notifications WHERE user_id=?", userId);
        update("DELETE FROM sessions WHERE user_id=?", userId);
        update("DELETE FROM users WHERE id=?", userId);
    }

    static void permanentlyDeleteStudentRecord(String studentId) throws SQLException {
        require(recordExists("SELECT id FROM students WHERE id=? AND COALESCE(is_archived,FALSE)=TRUE LIMIT 1", studentId), "Archive the staff member before permanently deleting");
        String linkedUserId = findLinkedUserId("students", studentId);
        update("DELETE FROM attendance WHERE student_id=?", studentId);
        update("DELETE FROM students WHERE id=?", studentId);
        deleteUserAccount(linkedUserId);
    }

    static void permanentlyDeleteTeacherRecord(String teacherId) throws SQLException {
        require(recordExists("SELECT id FROM teachers WHERE id=? AND COALESCE(is_archived,FALSE)=TRUE LIMIT 1", teacherId), "Archive the supervisor before permanently deleting");
        ResultSet subjectCount = query(
            "SELECT COUNT(*) AS total FROM subjects WHERE teacher_id=? AND COALESCE(is_archived,FALSE)=FALSE",
            teacherId
        );
        int ownedSubjects = subjectCount.next() ? subjectCount.getInt("total") : 0;
        require(ownedSubjects == 0, "Remove this supervisor from active work areas before permanently deleting");
        update("UPDATE subjects SET teacher_id=NULL WHERE teacher_id=? AND COALESCE(is_archived,FALSE)=TRUE", teacherId);
        String linkedUserId = findLinkedUserId("teachers", teacherId);
        update("DELETE FROM teachers WHERE id=?", teacherId);
        deleteUserAccount(linkedUserId);
    }

    static void permanentlyDeleteSubjectRecord(String subjectId) throws SQLException {
        require(recordExists("SELECT id FROM subjects WHERE id=? AND COALESCE(is_archived,FALSE)=TRUE LIMIT 1", subjectId), "Archive the subject before permanently deleting");
        update("DELETE FROM attendance WHERE subject_id=?", subjectId);
        update("DELETE FROM timetable WHERE subject_id=?", subjectId);
        update("DELETE FROM subjects WHERE id=?", subjectId);
    }

    static boolean subjectAccessibleToUser(Map<String, String> user, String subjectId) throws SQLException {
        Map<String, String> subject = resolveSubject(subjectId);
        if (subject == null) return false;
        if (hasRole(user, "admin")) return true;
        if (hasRole(user, "supervisor")) {
            return normalize(subject.get("teacher_id")).equals(normalize(user.get("profile_id")));
        }
        if (hasRole(user, "staff")) {
            return subjectMatchesStudent(subject, resolveStudent(user.get("profile_id")));
        }
        return false;
    }

    static String resolveQrSubjectId(Map<String, String> user, Map<String, String> student, String requestedSubjectId) throws SQLException {
        String subjectId = normalize(requestedSubjectId);
        if (!subjectId.isEmpty()) return subjectId;

        String course = normalizeCourse(student.get("course"));
        String yearLevel = normalize(student.get("year_level"));
        String section = normalizeSection(student.get("section"));
        StringBuilder sql = new StringBuilder(
            "SELECT s.id FROM subjects s" +
            " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
            " AND (NULLIF(TRIM(COALESCE(s.course,'')), '') IS NULL OR UPPER(s.course)=? OR UPPER(s.name)=?)" +
            " AND (s.year_level IS NULL OR s.year_level=?)" +
            " AND (NULLIF(TRIM(COALESCE(s.section,'')), '') IS NULL OR UPPER(s.section)=?)"
        );
        List<Object> params = new ArrayList<>();
        params.add(course);
        params.add(course);
        params.add(yearLevel);
        params.add(section);
        boolean supervisorScoped = false;
        if (hasRole(user, "supervisor")) {
            sql.append(" AND s.teacher_id=?");
            params.add(user.get("profile_id"));
            supervisorScoped = true;
        }
        sql.append(" ORDER BY s.id LIMIT 1");
        ResultSet rs = query(sql.toString(), params.toArray());
        if (rs.next()) return normalize(rs.getString("id"));

        String relaxedScopedId = findQrSubjectByWorkArea(user, student, supervisorScoped);
        if (!relaxedScopedId.isEmpty()) return relaxedScopedId;
        if (!supervisorScoped) return "";

        ResultSet fallback = query(
            "SELECT s.id FROM subjects s" +
            " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
            " AND (NULLIF(TRIM(COALESCE(s.course,'')), '') IS NULL OR UPPER(s.course)=? OR UPPER(s.name)=?)" +
            " AND (s.year_level IS NULL OR s.year_level=?)" +
            " AND (NULLIF(TRIM(COALESCE(s.section,'')), '') IS NULL OR UPPER(s.section)=?)" +
            " ORDER BY s.id LIMIT 1",
            course, course, yearLevel, section
        );
        if (fallback.next()) return normalize(fallback.getString("id"));
        return findQrSubjectByWorkArea(user, student, false);
    }

    static String findQrSubjectByWorkArea(Map<String, String> user, Map<String, String> student, boolean supervisorOnly) throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id,s.name,s.course,s.teacher_id FROM subjects s WHERE COALESCE(s.is_archived,FALSE)=FALSE"
        );
        List<Object> params = new ArrayList<>();
        if (supervisorOnly && hasRole(user, "supervisor")) {
            sql.append(" AND s.teacher_id=?");
            params.add(user.get("profile_id"));
        }
        sql.append(" ORDER BY s.id");
        ResultSet rs = query(sql.toString(), params.toArray());
        while (rs.next()) {
            Map<String, String> subject = new LinkedHashMap<>();
            subject.put("id", rs.getString("id"));
            subject.put("name", rs.getString("name"));
            subject.put("course", rs.getString("course"));
            if (subjectMatchesStudentWorkArea(subject, student)) return normalize(rs.getString("id"));
        }
        return "";
    }

    static boolean isValidTimeValue(String value) {
        return normalize(value).matches("^\\d{2}:\\d{2}(:\\d{2})?$");
    }

    static boolean isValidWeekday(String value) {
        return Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").contains(normalize(value));
    }

    static String findScheduleConflictMessage(String dayOfWeek, String startTime, String endTime) throws SQLException {
        ResultSet rs = query(
            "SELECT t.start_time,t.end_time,s.code,s.name," + computedFullNameSql("te") + " AS teacher_name" +
            " FROM timetable t" +
            " JOIN subjects s ON s.id=t.subject_id" +
            " LEFT JOIN teachers te ON te.id=s.teacher_id" +
            " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
            " AND t.day_of_week=?" +
            " AND t.start_time < ?" +
            " AND t.end_time > ?" +
            " ORDER BY t.start_time LIMIT 1",
            dayOfWeek, endTime, startTime
        );
        if (!rs.next()) return null;

        String code = normalize(rs.getString("code"));
        String name = normalize(rs.getString("name"));
        String teacherName = normalize(rs.getString("teacher_name"));
        String existingStart = normalize(rs.getString("start_time"));
        String existingEnd = normalize(rs.getString("end_time"));

        String courseLabel = !code.isEmpty() && !name.isEmpty()
            ? code + " - " + name
            : (!name.isEmpty() ? name : "another course");

        String teacherSuffix = teacherName.isEmpty() ? "" : " under " + teacherName;
        return "This time slot is already allotted to " + courseLabel + teacherSuffix +
            " (" + dayOfWeek + " " + existingStart + " - " + existingEnd + ").";
    }

    static void upsertAttendance(String studentId, String subjectId, String date, String timeIn,
                                 String status, String method, String latitude, String longitude,
                                 String locationAddress, String remarks, String markedBy) throws SQLException {
        ResultSet existing = query(
            "SELECT id FROM attendance WHERE student_id=? AND subject_id=? AND date=?",
            studentId, subjectId, date
        );
        if (existing.next()) {
            update(
                "UPDATE attendance SET time_in=?,status=?,method=?,latitude=?,longitude=?,location_address=?,remarks=?,marked_by=? WHERE id=?",
                blankToNull(timeIn), status, method, blankToNull(latitude), blankToNull(longitude),
                blankToNull(locationAddress), blankToNull(remarks), blankToNull(markedBy), existing.getString("id")
            );
        } else {
            update(
                "INSERT INTO attendance(student_id,subject_id,date,time_in,status,method,latitude,longitude,location_address,remarks,marked_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                studentId, subjectId, date, blankToNull(timeIn), status, method,
                blankToNull(latitude), blankToNull(longitude), blankToNull(locationAddress), blankToNull(remarks), blankToNull(markedBy)
            );
        }
    }

    static void syncLowAttendanceNotifications() throws SQLException {
        List<Map<String, String>> alerts = new ArrayList<>();
        ResultSet alertRs = query(
            "SELECT s.id, s.user_id, " + computedFullNameSql("s") + " AS full_name, s.student_id," +
            " ROUND(SUM(a.status='present')*100.0/NULLIF(COUNT(a.id),0),1) AS rate" +
            " FROM students s JOIN attendance a ON a.student_id=s.id" +
            " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
            " GROUP BY s.id HAVING rate < 75 ORDER BY rate"
        );
        while (alertRs.next()) {
            Map<String, String> alert = new LinkedHashMap<>();
            alert.put("id", alertRs.getString("id"));
            alert.put("user_id", alertRs.getString("user_id"));
            alert.put("full_name", alertRs.getString("full_name"));
            alert.put("student_id", alertRs.getString("student_id"));
            alert.put("rate", alertRs.getString("rate"));
            alerts.add(alert);
        }

        ResultSet users = query(
            "SELECT id FROM users WHERE role IN ('admin','supervisor') AND COALESCE(is_archived,FALSE)=FALSE"
        );
        List<String> userIds = new ArrayList<>();
        while (users.next()) userIds.add(users.getString("id"));

        for (String userId : userIds) {
            for (Map<String, String> alert : alerts) {
                String message = "Low attendance alert: " + alert.get("full_name") + " is at " + alert.get("rate") + "% attendance.";
                ResultSet existing = query(
                    "SELECT id FROM notifications WHERE user_id=? AND message=? AND DATE(created_at)=CURDATE() LIMIT 1",
                    userId, message
                );
                if (!existing.next()) {
                    update("INSERT INTO notifications(user_id,message,type,is_read) VALUES(?,?,?,FALSE)", userId, message, "warning");
                }
            }
        }

        for (Map<String, String> alert : alerts) {
            String studentUserId = normalize(alert.get("user_id"));
            if (studentUserId.isEmpty()) continue;
            String message = "Low attendance notice: Your attendance is at " + alert.get("rate") + "%. Open this notification to review your present and absent dates.";
            ResultSet existing = query(
                "SELECT id FROM notifications WHERE user_id=? AND message=? AND DATE(created_at)=CURDATE() LIMIT 1",
                studentUserId, message
            );
            if (!existing.next()) {
                update("INSERT INTO notifications(user_id,message,type,is_read) VALUES(?,?,?,FALSE)", studentUserId, message, "warning");
            }
        }
    }

    static String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    static String buildStudentLowAttendanceDetailJson(String studentProfileId) throws SQLException {
        Map<String, String> student = resolveStudent(studentProfileId);
        require(student != null, "Staff not found");

        ResultSet summary = query(
            "SELECT COUNT(a.id) AS total_records," +
            " COALESCE(SUM(CASE WHEN a.status='present' THEN 1 ELSE 0 END),0) AS present_count," +
            " COALESCE(SUM(CASE WHEN a.status='absent' THEN 1 ELSE 0 END),0) AS absent_count," +
            " COALESCE(ROUND(SUM(CASE WHEN a.status='present' THEN 1 ELSE 0 END)*100.0/NULLIF(COUNT(a.id),0),1),0) AS attendance_rate" +
            " FROM attendance a WHERE a.student_id=?",
            studentProfileId
        );
        summary.next();

        List<String> presentDates = new ArrayList<>();
        List<String> absentDates = new ArrayList<>();
        ResultSet records = query(
            "SELECT a.date, a.status, COALESCE(sub.name, '') AS subject_name" +
            " FROM attendance a" +
            " LEFT JOIN subjects sub ON sub.id=a.subject_id" +
            " WHERE a.student_id=?" +
            " ORDER BY a.date DESC, a.time_in DESC",
            studentProfileId
        );
        while (records.next()) {
            String date = normalize(records.getString("date"));
            String subjectName = normalize(records.getString("subject_name"));
            String label = subjectName.isEmpty() ? date : date + " - " + subjectName;
            String status = normalize(records.getString("status")).toLowerCase(Locale.ROOT);
            if (status.equals("present")) presentDates.add(label);
            if (status.equals("absent")) absentDates.add(label);
        }

        return "{"
            + "\"student_name\":\"" + escapeJson(student.get("full_name")) + "\""
            + ",\"student_id\":\"" + escapeJson(student.get("student_id")) + "\""
            + ",\"attendance_rate\":\"" + escapeJson(summary.getString("attendance_rate")) + "\""
            + ",\"total_records\":\"" + escapeJson(summary.getString("total_records")) + "\""
            + ",\"present_count\":\"" + escapeJson(summary.getString("present_count")) + "\""
            + ",\"absent_count\":\"" + escapeJson(summary.getString("absent_count")) + "\""
            + ",\"present_dates\":" + jsonStringArray(presentDates)
            + ",\"absent_dates\":" + jsonStringArray(absentDates)
            + "}";
    }

    // ── OTP / Email helpers ────────────────────────────────────
    static List<String> findUserIdsByRoles(String... roles) throws SQLException {
        List<String> result = new ArrayList<>();
        if (roles == null || roles.length == 0) return result;

        StringJoiner placeholders = new StringJoiner(",", "(", ")");
        List<Object> params = new ArrayList<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            placeholders.add("?");
            params.add(role);
        }
        if (params.isEmpty()) return result;

        ResultSet rs = query(
            "SELECT id FROM users WHERE role IN " + placeholders +
            " AND COALESCE(is_archived,FALSE)=FALSE ORDER BY id",
            params.toArray()
        );
        while (rs.next()) result.add(rs.getString("id"));
        return result;
    }

    static String findSubjectTeacherUserId(String subjectId) throws SQLException {
        if (!isPositiveInteger(subjectId)) return "";
        ResultSet rs = query(
            "SELECT t.user_id FROM subjects s LEFT JOIN teachers t ON t.id=s.teacher_id WHERE s.id=? LIMIT 1",
            subjectId
        );
        return rs.next() ? normalize(rs.getString("user_id")) : "";
    }

    static void createNotificationForUsers(Collection<String> userIds, String message, String type) throws SQLException {
        createNotificationForUsers(userIds, message, type, null);
    }

    static void createNotificationForUsers(Collection<String> userIds, String message, String type, String details) throws SQLException {
        if (userIds == null || userIds.isEmpty() || message == null || message.isBlank()) return;
        String notificationType = normalize(type).isEmpty() ? "info" : normalize(type).toLowerCase(Locale.ROOT);
        String normalizedDetails = normalize(details);
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) continue;
            ResultSet existing = query(
                "SELECT id FROM notifications WHERE user_id=? AND message=? AND COALESCE(details,'')=? AND DATE(created_at)=CURDATE() LIMIT 1",
                userId, message, normalizedDetails
            );
            if (!existing.next()) {
                update(
                    "INSERT INTO notifications(user_id,message,details,type,is_read) VALUES(?,?,?,?,FALSE)",
                    userId, message, blankToNull(normalizedDetails), notificationType
                );
            }
        }
    }

    static void createNotificationForRoles(String message, String type, String... roles) throws SQLException {
        createNotificationForUsers(findUserIdsByRoles(roles), message, type);
    }

    static String formatCoordinate(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "";
        try {
            return String.format(Locale.US, "%.6f", Double.parseDouble(normalized));
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    static String buildLocationMapUrl(String latitude, String longitude) {
        String lat = formatCoordinate(latitude);
        String lng = formatCoordinate(longitude);
        if (lat.isEmpty() || lng.isEmpty()) return "";
        return "https://www.google.com/maps?q=" + lat + "," + lng;
    }

    static String buildAttendanceNotificationDetails(Map<String, String> student, String subjectName, String method,
                                                     String attendanceDate, String attendanceTime,
                                                     String latitude, String longitude) {
        List<String> lines = new ArrayList<>();
        lines.add("Staff: " + normalize(student.get("full_name")) + " (" + normalize(student.get("student_id")) + ")");
        lines.add("Work Area: " + (isBlank(subjectName) ? "-" : subjectName));
        lines.add("Method: " + ("geo".equalsIgnoreCase(method) ? "Location check-in" : "QR Scan"));
        lines.add("Date: " + normalize(attendanceDate));
        lines.add("Time: " + normalize(attendanceTime));

        String lat = formatCoordinate(latitude);
        String lng = formatCoordinate(longitude);
        if (!lat.isEmpty() && !lng.isEmpty()) {
            lines.add("Coordinates: " + lat + ", " + lng);
            lines.add("Map: " + buildLocationMapUrl(lat, lng));
        } else {
            lines.add("Coordinates: Location was not captured on this device.");
        }

        return String.join("\n", lines);
    }

    static void notifyGeoBoundaryAttempt(String subjectId, String subjectName, Map<String, String> student,
                                         String attendanceDate, String attendanceTime,
                                         String latitude, String longitude) throws SQLException {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        String teacherUserId = findSubjectTeacherUserId(subjectId);
        if (!teacherUserId.isEmpty()) recipients.add(teacherUserId);
        if (recipients.isEmpty()) return;

        String message = "Location attendance attempt outside branch by " + normalize(student.get("full_name")) +
            " in " + (isBlank(subjectName) ? "the selected work area" : subjectName) + ".";
        String details = buildAttendanceNotificationDetails(
            student,
            subjectName,
            "geo",
            attendanceDate,
            attendanceTime,
            latitude,
            longitude
        ) + "\nResult: Outside Latte & Letters branch boundary.";
        createNotificationForUsers(recipients, message, "warning", details);
    }

    static void notifyAttendanceTracking(String subjectId, String subjectName, Map<String, String> student, String method,
                                         String attendanceDate, String attendanceTime,
                                         String latitude, String longitude) throws SQLException {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        String teacherUserId = findSubjectTeacherUserId(subjectId);
        if (!teacherUserId.isEmpty()) recipients.add(teacherUserId);
        if (recipients.isEmpty()) return;

        String methodLabel = "geo".equalsIgnoreCase(method) ? "Location attendance" : "QR attendance";
        String message = methodLabel + " recorded for " + normalize(student.get("full_name")) +
            " in " + (isBlank(subjectName) ? "the selected work area" : subjectName) + ".";
        String details = buildAttendanceNotificationDetails(
            student,
            subjectName,
            method,
            attendanceDate,
            attendanceTime,
            latitude,
            longitude
        );
        createNotificationForUsers(recipients, message, "info", details);
    }

    static String findSubjectName(String subjectId) throws SQLException {
        if (!isPositiveInteger(subjectId)) return "";
        ResultSet rs = query("SELECT name FROM subjects WHERE id=? LIMIT 1", subjectId);
        return rs.next() ? normalize(rs.getString("name")) : "";
    }

    static String generateOtp() {
        return String.format("%06d", new Random().nextInt(1000000));
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static boolean isMailConfigured() {
        return MAIL_ENABLED &&
               MAIL_HOST != null && !MAIL_HOST.isBlank() &&
               MAIL_PORT > 0 &&
               MAIL_FROM != null && !MAIL_FROM.isBlank() &&
               MAIL_PASS != null && !MAIL_PASS.isBlank() &&
               !MAIL_FROM.equals("your_gmail@gmail.com") &&
               !MAIL_PASS.equals("xxxx xxxx xxxx xxxx");
    }

    static String sanitizeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    static String escapeHtml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    static String mailFromDomain() {
        String from = sanitizeHeader(MAIL_FROM);
        int at = from.lastIndexOf('@');
        if (at >= 0 && at < from.length() - 1) {
            return from.substring(at + 1).replaceAll("[^A-Za-z0-9.-]", "");
        }
        return "latteletters.local";
    }

    static String mailDateHeader() {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now());
    }

    static String otpPurposeTitle(String purpose) {
        switch (purpose) {
            case "register": return "Account verification";
            case "reset_password": return "Password reset";
            default: return "Profile update";
        }
    }

    static String otpPurposeIntro(String purpose) {
        switch (purpose) {
            case "register":
                return "Use this code to finish creating your Latte & Letters account.";
            case "reset_password":
                return "Use this code to continue resetting your Latte & Letters password.";
            default:
                return "Use this code to confirm your Latte & Letters profile changes.";
        }
    }

    static String otpPurposeNote(String purpose) {
        switch (purpose) {
            case "register":
                return "If you did not try to create an account, you can ignore this email.";
            case "reset_password":
                return "If you did not request a password reset, you can ignore this email.";
            default:
                return "If you did not request this profile change, you can ignore this email.";
        }
    }

    static String buildOtpPlainTextEmail(String otp, String purpose) {
        return "Latte & Letters " + otpPurposeTitle(purpose) + "\n\n"
            + otpPurposeIntro(purpose) + "\n\n"
            + "Verification code: " + otp + "\n"
            + "Valid for: 3 minutes\n\n"
            + "Enter this code in Latte & Letters. Never share this code with anyone.\n\n"
            + otpPurposeNote(purpose) + "\n\n"
            + "Latte & Letters\n"
            + "Cafe-library staff attendance system";
    }

    static String buildOtpHtmlEmail(String otp, String purpose) {
        String title = escapeHtml(otpPurposeTitle(purpose));
        String intro = escapeHtml(otpPurposeIntro(purpose));
        String note = escapeHtml(otpPurposeNote(purpose));
        String safeOtp = escapeHtml(otp);
        return "<!doctype html>\n"
            + "<html>\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>Latte &amp; Letters verification code</title>\n"
            + "</head>\n"
            + "<body style=\"margin:0;padding:0;background:#eef8f1;color:#173522;font-family:Arial,Helvetica,sans-serif;\">\n"
            + "  <div style=\"display:none;max-height:0;overflow:hidden;color:transparent;opacity:0;\">Your Latte &amp; Letters code is " + safeOtp + ". It is valid for 3 minutes.</div>\n"
            + "  <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#eef8f1;padding:24px 12px;\">\n"
            + "    <tr>\n"
            + "      <td align=\"center\">\n"
            + "        <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:520px;background:#ffffff;border:1px solid #cfe9d7;border-radius:16px;overflow:hidden;box-shadow:0 14px 34px rgba(25,146,74,.14);\">\n"
            + "          <tr>\n"
            + "            <td style=\"background:#6E473B;padding:22px 24px;color:#ffffff;\">\n"
            + "              <div style=\"font-size:22px;font-weight:800;letter-spacing:.3px;\">Latte &amp; Letters</div>\n"
            + "              <div style=\"margin-top:4px;font-size:13px;color:#dfffea;\">Cafe-library staff attendance system</div>\n"
            + "            </td>\n"
            + "          </tr>\n"
            + "          <tr>\n"
            + "            <td style=\"padding:26px 24px 10px;\">\n"
            + "              <div style=\"font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.08em;color:#168047;\">Verification Code</div>\n"
            + "              <h1 style=\"margin:10px 0 8px;font-size:24px;line-height:1.25;color:#163522;\">" + title + "</h1>\n"
            + "              <p style=\"margin:0;color:#466352;font-size:15px;line-height:1.6;\">" + intro + "</p>\n"
            + "            </td>\n"
            + "          </tr>\n"
            + "          <tr>\n"
            + "            <td align=\"center\" style=\"padding:18px 24px 8px;\">\n"
            + "              <div style=\"display:inline-block;background:#e9f9ee;border:1px solid #bde9cb;border-radius:14px;padding:16px 26px;color:#0d3b1f;font-size:34px;font-weight:800;letter-spacing:8px;line-height:1;\">" + safeOtp + "</div>\n"
            + "            </td>\n"
            + "          </tr>\n"
            + "          <tr>\n"
            + "            <td style=\"padding:8px 24px 24px;\">\n"
            + "              <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f6fcf8;border:1px solid #d8efdf;border-radius:12px;\">\n"
            + "                <tr>\n"
            + "                  <td style=\"padding:14px 16px;font-size:14px;line-height:1.6;color:#385844;\">\n"
            + "                    This code expires in <strong style=\"color:#6E473B;\">3 minutes</strong>. Enter it only inside Latte &amp; Letters and do not share it with anyone.\n"
            + "                  </td>\n"
            + "                </tr>\n"
            + "              </table>\n"
            + "              <p style=\"margin:18px 0 0;color:#688170;font-size:13px;line-height:1.6;\">" + note + "</p>\n"
            + "            </td>\n"
            + "          </tr>\n"
            + "          <tr>\n"
            + "            <td style=\"background:#f4fbf6;border-top:1px solid #d8efdf;padding:16px 24px;color:#6d8575;font-size:12px;line-height:1.5;\">\n"
            + "              Sent by Latte &amp; Letters for account security. This is an automated message.\n"
            + "            </td>\n"
            + "          </tr>\n"
            + "        </table>\n"
            + "      </td>\n"
            + "    </tr>\n"
            + "  </table>\n"
            + "</body>\n"
            + "</html>";
    }

    static void smtpWriteLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    static String smtpReadResponse(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) throw new IOException("SMTP server closed the connection");
        String latest = line;
        while (latest.length() >= 4 && latest.charAt(3) == '-') {
            latest = reader.readLine();
            if (latest == null) throw new IOException("SMTP server closed the connection");
        }
        return latest;
    }

    static void smtpExpect(BufferedReader reader, String... expectedCodes) throws IOException {
        String response = smtpReadResponse(reader);
        for (String code : expectedCodes) {
            if (response.startsWith(code)) return;
        }
        throw new IOException(response);
    }

    static void smtpWriteDataLine(BufferedWriter writer, String line) throws IOException {
        String safeLine = line == null ? "" : line.replace("\r", "");
        if (safeLine.startsWith(".")) safeLine = "." + safeLine;
        writer.write(safeLine);
        writer.write("\r\n");
    }

    static void sendOtpEmail(String toEmail, String otp, String purpose) throws IOException {
        if (!isMailConfigured()) {
            throw new IOException("Email sending is not configured. Update config.properties first.");
        }

        String subject;
        switch (purpose) {
            case "register":
                subject = "Your Latte & Letters verification code";
                break;
            case "reset_password":
                subject = "Your Latte & Letters password reset code";
                break;
            default:
                subject = "Your Latte & Letters profile update code";
        }

        String plainBody = buildOtpPlainTextEmail(otp, purpose);
        String htmlBody = buildOtpHtmlEmail(otp, purpose);
        String boundary = "LATTELETTERS-" + UUID.randomUUID().toString().replace("-", "");
        String safeFrom = sanitizeHeader(MAIL_FROM);
        String safeFromName = sanitizeHeader(MAIL_FROM_NAME);
        if (safeFromName.isEmpty()) safeFromName = "Latte & Letters";
        String safeTo = sanitizeHeader(toEmail);
        String messageId = "<" + UUID.randomUUID().toString() + "@" + mailFromDomain() + ">";

        try (Socket plainSocket = new Socket()) {
            plainSocket.connect(new InetSocketAddress(MAIL_HOST, MAIL_PORT), 10000);
            plainSocket.setSoTimeout(10000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(plainSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(plainSocket.getOutputStream(), StandardCharsets.UTF_8));

            smtpExpect(reader, "220");
            smtpWriteLine(writer, "EHLO localhost");
            smtpExpect(reader, "250");
            smtpWriteLine(writer, "STARTTLS");
            smtpExpect(reader, "220");

            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket secureSocket = (SSLSocket) sslFactory.createSocket(plainSocket, MAIL_HOST, MAIL_PORT, true)) {
                secureSocket.setUseClientMode(true);
                secureSocket.setSoTimeout(10000);
                secureSocket.startHandshake();

                reader = new BufferedReader(new InputStreamReader(secureSocket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(secureSocket.getOutputStream(), StandardCharsets.UTF_8));

                smtpWriteLine(writer, "EHLO localhost");
                smtpExpect(reader, "250");
                smtpWriteLine(writer, "AUTH LOGIN");
                smtpExpect(reader, "334");
                smtpWriteLine(writer, Base64.getEncoder().encodeToString(MAIL_FROM.getBytes(StandardCharsets.UTF_8)));
                smtpExpect(reader, "334");
                smtpWriteLine(writer, Base64.getEncoder().encodeToString(MAIL_PASS.getBytes(StandardCharsets.UTF_8)));
                smtpExpect(reader, "235");
                smtpWriteLine(writer, "MAIL FROM:<" + safeFrom + ">");
                smtpExpect(reader, "250");
                smtpWriteLine(writer, "RCPT TO:<" + safeTo + ">");
                smtpExpect(reader, "250", "251");
                smtpWriteLine(writer, "DATA");
                smtpExpect(reader, "354");

                smtpWriteDataLine(writer, "Date: " + mailDateHeader());
                smtpWriteDataLine(writer, "From: \"" + safeFromName.replace("\"", "") + "\" <" + safeFrom + ">");
                smtpWriteDataLine(writer, "Sender: <" + safeFrom + ">");
                smtpWriteDataLine(writer, "Reply-To: \"" + safeFromName.replace("\"", "") + "\" <" + safeFrom + ">");
                smtpWriteDataLine(writer, "To: <" + safeTo + ">");
                smtpWriteDataLine(writer, "Subject: " + sanitizeHeader(subject));
                smtpWriteDataLine(writer, "Message-ID: " + messageId);
                smtpWriteDataLine(writer, "X-Auto-Response-Suppress: All");
                smtpWriteDataLine(writer, "Auto-Submitted: auto-generated");
                smtpWriteDataLine(writer, "MIME-Version: 1.0");
                smtpWriteDataLine(writer, "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"");
                smtpWriteDataLine(writer, "");
                smtpWriteDataLine(writer, "--" + boundary);
                smtpWriteDataLine(writer, "Content-Type: text/plain; charset=UTF-8");
                smtpWriteDataLine(writer, "Content-Transfer-Encoding: 8bit");
                smtpWriteDataLine(writer, "");
                for (String line : plainBody.replace("\r", "").split("\n", -1)) {
                    smtpWriteDataLine(writer, line);
                }
                smtpWriteDataLine(writer, "");
                smtpWriteDataLine(writer, "--" + boundary);
                smtpWriteDataLine(writer, "Content-Type: text/html; charset=UTF-8");
                smtpWriteDataLine(writer, "Content-Transfer-Encoding: 8bit");
                smtpWriteDataLine(writer, "");
                for (String line : htmlBody.replace("\r", "").split("\n", -1)) {
                    smtpWriteDataLine(writer, line);
                }
                smtpWriteDataLine(writer, "");
                smtpWriteDataLine(writer, "--" + boundary + "--");
                smtpWriteLine(writer, ".");
                smtpExpect(reader, "250");
                smtpWriteLine(writer, "QUIT");
            }
        }
    }

    // ==========================================================
    // STATIC FILES
    // ==========================================================
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            File file = new File("web" + path);
            if (!file.exists()) { sendJson(ex, 404, "{\"error\":\"Not found\"}"); return; }
            byte[] bytes = Files.readAllBytes(file.toPath());
            String ct = path.endsWith(".css") ? "text/css"
                      : path.endsWith(".js")  ? "application/javascript"
                      : path.endsWith(".png") ? "image/png"
                      : path.endsWith(".ico") ? "image/x-icon"
                      : "text/html";
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    // ==========================================================
    // OTP HANDLERS
    // ==========================================================

    static class SendOtpHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String email   = normalizeEmail(b.get("email"));
                String purpose = normalize(b.getOrDefault("purpose", "register")).toLowerCase(Locale.ROOT);
                if (purpose.equals("register")) {
                    Map<String, String> user = validateToken(ex);
                    if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only admin can manage registration\"}"); return; }
                }
                if (email.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Email is required\"}"); return;
                }
                String emailError = getEmailValidationError(email);
                if (emailError != null) {
                    sendJson(ex, 400, "{\"error\":\"" + escapeJson(emailError) + "\"}"); return;
                }
                if (!isValidOtpPurpose(purpose)) {
                    sendJson(ex, 400, "{\"error\":\"Invalid OTP purpose\"}"); return;
                }
                clearVerifiedOtp(email, purpose);
                if (purpose.equals("register") && hasReachedEmailUsageLimit(email, null)) {
                    sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                }
                // For reset_password: verify email exists in users table
                if (purpose.equals("reset_password")) {
                    ResultSet rs = query("SELECT id FROM users WHERE LOWER(email)=?", email);
                    if (!rs.next()) {
                        sendJson(ex, 404, "{\"error\":\"No account found with that email\"}"); return;
                    }
                }
                int activeOtpSeconds = getActiveOtpRemainingSeconds(email, purpose);
                if (activeOtpSeconds > 0) {
                    sendJson(ex, 429, "{\"error\":\"A verification code is already active. Please wait " + activeOtpSeconds + " seconds before requesting a new one.\"}");
                    return;
                }
                if (!isMailConfigured()) {
                    sendJson(ex, 500, "{\"error\":\"OTP email is not configured yet. Set mail.enabled=true plus your Gmail sender and App Password in config.properties.\"}");
                    return;
                }

                String otp = generateOtp();
                final String finalEmail = email;
                final String finalOtp = otp;
                final String finalPurpose = purpose;
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                java.util.concurrent.Future<?> future = executor.submit(() -> {
                    try { sendOtpEmail(finalEmail, finalOtp, finalPurpose); }
                    catch (Exception e1) { throw new RuntimeException(e1.getMessage(), e1); }
                });
                try {
                    future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    future.cancel(true);
                    sendJson(ex, 500, "{\"error\":\"OTP email timed out. Please check your internet connection and Gmail sender settings.\"}");
                    return;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    String message = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                        ? cause.getMessage()
                        : "Unknown email sending error";
                    System.out.println("[OTP ERROR] " + message);
                    sendJson(ex, 500, "{\"error\":\"Failed to send OTP email: " + escapeJson(message) + "\"}");
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    sendJson(ex, 500, "{\"error\":\"Email sending was interrupted\"}");
                    return;
                } finally {
                    executor.shutdownNow();
                }
                update("INSERT INTO otp_verifications(email,otp,purpose,expires_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 3 MINUTE))",
                    email, otp, purpose);

                System.out.println("[OTP] Sent to " + email + " purpose=" + purpose);
                sendJson(ex, 200, "{\"message\":\"OTP sent to " + escapeJson(email) + "\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class VerifyOtpHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String email   = normalizeEmail(b.get("email"));
                String otp     = normalize(b.get("otp"));
                String purpose = normalize(b.getOrDefault("purpose", "register")).toLowerCase(Locale.ROOT);
                if (email.isEmpty() || otp.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"email and otp required\"}"); return;
                }
                String emailError = getEmailValidationError(email);
                if (emailError != null) {
                    sendJson(ex, 400, "{\"error\":\"" + escapeJson(emailError) + "\"}"); return;
                }
                if (!otp.matches("^\\d{6}$")) {
                    sendJson(ex, 400, "{\"error\":\"OTP must be 6 digits\"}"); return;
                }
                if (!isValidOtpPurpose(purpose)) {
                    sendJson(ex, 400, "{\"error\":\"Invalid OTP purpose\"}"); return;
                }
                ResultSet rs = query(
                    "SELECT id FROM otp_verifications WHERE LOWER(email)=? AND otp=? AND purpose=? AND used=FALSE AND expires_at > NOW()",
                    email, otp, purpose);
                if (!rs.next()) {
                    sendJson(ex, 400, "{\"error\":\"Invalid or expired code\"}"); return;
                }
                int otpId = rs.getInt("id");
                update("UPDATE otp_verifications SET used=TRUE WHERE id=?", otpId);
                rememberVerifiedOtp(email, purpose);
                sendJson(ex, 200, "{\"verified\":true}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class UsernameCheckHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            if (!ex.getRequestMethod().equals("GET")) {
                sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                String username = normalize(qs.get("username"));
                if (username.isEmpty()) {
                    sendJson(ex, 200, "{\"available\":false,\"error\":\"Username is required\"}");
                    return;
                }
                if (!isValidUsername(username)) {
                    sendJson(ex, 200, "{\"available\":false,\"error\":\"Username must be 3-30 characters using letters, numbers, or underscores only\"}");
                    return;
                }
                if (usernameExists(username, null)) {
                    sendJson(ex, 200, "{\"available\":false,\"error\":\"Username already taken\"}");
                    return;
                }
                sendJson(ex, 200, "{\"available\":true}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    // ==========================================================
    // AUTH HANDLERS
    // ==========================================================

    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String username = normalize(b.get("username"));
                String password = b.get("password");
                if (username == null || password == null) {
                    sendJson(ex, 400, "{\"error\":\"Username and password required\"}"); return;
                }
                ResultSet rs = query(
                    "SELECT u.id, u.username, u.role, u.password," +
                    " COALESCE(" + nullableFullNameSql("t") + ", " + nullableFullNameSql("s") + ", u.username) AS full_name," +
                    " s.id AS student_profile_id, t.id AS teacher_profile_id, s.student_id AS student_number" +
                    " FROM users u" +
                    " LEFT JOIN teachers t ON t.user_id = u.id AND COALESCE(t.is_archived,FALSE)=FALSE" +
                    " LEFT JOIN students s ON s.user_id = u.id AND COALESCE(s.is_archived,FALSE)=FALSE" +
                    " WHERE u.username = ? AND COALESCE(u.is_archived,FALSE)=FALSE", username);
                if (rs.next()) {
                    String role = rs.getString("role");
                    if (("staff".equals(role) && isBlank(rs.getString("student_profile_id"))) ||
                        ("supervisor".equals(role) && isBlank(rs.getString("teacher_profile_id")))) {
                        sendJson(ex, 401, "{\"error\":\"Invalid credentials\"}"); return;
                    }
                    String storedPassword = rs.getString("password");
                    if (!verifyPassword(password, storedPassword)) {
                        sendJson(ex, 401, "{\"error\":\"Invalid credentials\"}"); return;
                    }
                    upgradeLegacyPasswordIfNeeded(rs.getString("id"), password, storedPassword);
                    String token = UUID.randomUUID().toString();
                    Map<String, String> user = new HashMap<>();
                    user.put("id",        rs.getString("id"));
                    user.put("username",  rs.getString("username"));
                    user.put("role",      role);
                    user.put("full_name", rs.getString("full_name"));
                    String profileId = role.equals("staff")
                        ? rs.getString("student_profile_id")
                        : role.equals("supervisor")
                            ? rs.getString("teacher_profile_id")
                            : rs.getString("id");
                    user.put("profile_id", profileId == null ? "" : profileId);
                    user.put("student_number", rs.getString("student_number") == null ? "" : rs.getString("student_number"));
                    tokenStore.put(token, user);
                    persistSession(user.get("id"), token);
                    sendJson(ex, 200,
                        "{\"token\":\"" + token + "\"" +
                        ",\"role\":\""  + user.get("role")      + "\"" +
                        ",\"name\":\""  + user.get("full_name") + "\"" +
                        ",\"id\":\""    + user.get("id")        + "\"" +
                        ",\"profile_id\":\"" + user.get("profile_id") + "\"" +
                        ",\"student_number\":\"" + user.get("student_number") + "\"}");
                } else {
                    sendJson(ex, 401, "{\"error\":\"Invalid credentials\"}");
                }
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only admin can manage registration\"}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String username = normalize(b.get("username"));
                String password = b.get("password");
                String role     = normalize(b.getOrDefault("role", "staff")).toLowerCase(Locale.ROOT);
                NameParts nameParts = resolveNameParts(b, b.getOrDefault("full_name", username));
                String fullName = nameParts.fullName;
                String email    = normalizeEmail(b.getOrDefault("email", ""));
                String phone    = normalize(b.getOrDefault("phone", ""));
                String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                String college = normalize(b.getOrDefault("college", "")).replaceAll("\\s+", " ");
                String specialization = normalize(b.getOrDefault("specialization", "")).replaceAll("\\s+", " ");
                String yearLevel = normalize(b.getOrDefault("year_level", "1"));
                int age = calculateAgeFromBirthDate(birthDate);
                if (username.isEmpty() || password == null || password.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Username and password are required\"}"); return;
                }
                require(isValidUsername(username), "Username must be 3-30 characters using letters, numbers, or underscores only");
                require(password.length() >= 6, "Password must be at least 6 characters");
                require(isAllowedSelfRegisterRole(role), "Only staff or supervisor accounts can be created here");
                require(isValidFullName(fullName), "Enter a valid full name");
                requireValidEmail(email);
                require(!isBlank(phone), "Phone number is required");
                require(isValidPhone(phone), "Enter a valid phone number");
                require(phone.length() == 11, "Phone number must be exactly 11 digits");
                require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                require(isLagunaProvince(province), "Province must be LAGUNA");
                String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                require(municipalityError == null, municipalityError);
                String barangayError = getAddressComponentError("Barangay", barangay, 100);
                require(barangayError == null, barangayError);
                if (role.equals("staff")) {
                    require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                    require(!college.isEmpty(), "Branch / department is required");
                    require(isValidCourse(b.get("course")), "Enter a valid work area");
                    require(isValidSection(b.get("section")), "Enter a valid shift group");
                } else {
                    require(!isBlank(b.get("department")), "Branch / department is required");
                    require(isValidCourse(b.get("subject")), "Enter a valid assigned area");
                }
                if (!consumeVerifiedOtp(email, "register")) {
                    sendJson(ex, 400, "{\"error\":\"Verify the OTP sent to your email before creating the account\"}"); return;
                }
                if (usernameExists(username, null)) {
                    sendJson(ex, 409, "{\"error\":\"Username already taken\"}"); return;
                }
                if (hasReachedEmailUsageLimit(email, null)) {
                    sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                }
                int uid = update("INSERT INTO users(username,password,email,role) VALUES(?,?,?,?)",
                    username, hashPassword(password), email, role);
                if (role.equals("staff")) {
                    String sid = generateStudentId(yearLevel);
                    update("INSERT INTO students(user_id,student_id,first_name,middle_name,last_name,suffix,email,phone,date_of_birth,age,province,municipality,barangay,college,course,specialization,year_level,section) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        uid, sid, blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email,
                        phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay,
                        college, normalizeCourse(b.getOrDefault("course","")), specialization, yearLevel, normalizeSection(b.getOrDefault("section","")));
                    createNotificationForRoles(
                        "New staff account created by admin: " + fullName + " (" + sid + ").",
                        "warning",
                        "admin"
                    );
                    sendJson(ex, 201,
                        "{\"message\":\"Staff account created\"" +
                        ",\"student_id\":\"" + escapeJson(sid) + "\"}");
                    return;
                } else if (role.equals("supervisor")) {
                    update("INSERT INTO teachers(user_id,first_name,middle_name,last_name,suffix,email,phone,date_of_birth,age,province,municipality,barangay,department,subject,specialization) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        uid, blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email,
                        phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay,
                        normalize(b.getOrDefault("department","")), normalizeCourse(b.getOrDefault("subject","")),
                        blankToNull(normalizeAddressComponent(b.getOrDefault("specialization", ""))));
                    createNotificationForRoles(
                        "New supervisor account created by admin: " + fullName + ".",
                        "warning",
                        "admin"
                    );
                }
                sendJson(ex, 201, "{\"message\":\"Supervisor account created\"}");
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            try {
                if (auth != null && auth.startsWith("Bearer ")) clearSession(auth.substring(7));
                sendJson(ex, 200, "{\"message\":\"Logged out\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    static class ResetPasswordHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String email   = normalizeEmail(b.get("email"));
                String newPass = b.get("new_password");
                if (email.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Email is required\"}"); return;
                }
                String emailError = getEmailValidationError(email);
                if (emailError != null) {
                    sendJson(ex, 400, "{\"error\":\"" + escapeJson(emailError) + "\"}"); return;
                }
                if (newPass == null || newPass.length() < 6) {
                    sendJson(ex, 400, "{\"error\":\"Password must be at least 6 characters\"}"); return;
                }
                ResultSet rs = query("SELECT id FROM users WHERE LOWER(email)=?", email);
                if (!rs.next()) {
                    sendJson(ex, 404, "{\"error\":\"No account found with that email\"}"); return;
                }
                if (!consumeVerifiedOtp(email, "reset_password")) {
                    sendJson(ex, 400, "{\"error\":\"Verify the OTP before resetting your password\"}"); return;
                }
                update("UPDATE users SET password=? WHERE LOWER(email)=?", hashPassword(newPass), email);
                sendJson(ex, 200, "{\"message\":\"Password reset successfully.\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    static class ChangePasswordHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                Map<String, String> b = parseJson(readBody(ex));
                String currentPass = b.get("current_password");
                String newPass     = b.get("new_password");
                if (currentPass == null || currentPass.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"Current password is required\"}"); return;
                }
                if (newPass == null || newPass.length() < 6) {
                    sendJson(ex, 400, "{\"error\":\"Password must be at least 6 characters\"}"); return;
                }
                if (currentPass.equals(newPass)) {
                    sendJson(ex, 400, "{\"error\":\"New password must be different from the current password\"}"); return;
                }
                ResultSet rs = query("SELECT password FROM users WHERE id=?", user.get("id"));
                if (!rs.next() || !verifyPassword(currentPass, rs.getString("password"))) {
                    sendJson(ex, 400, "{\"error\":\"Current password is incorrect\"}"); return;
                }
                update("UPDATE users SET password=? WHERE id=?", hashPassword(newPass), user.get("id"));
                sendJson(ex, 200, "{\"message\":\"Password updated successfully.\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // ==========================================================
    // PROFILE HANDLER
    // ==========================================================
    static class ProfileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                String role = user.get("role");
                String uid  = user.get("id");
                if (ex.getRequestMethod().equals("GET")) {
                    ResultSet rs;
                    if (role.equals("staff")) {
                        rs = query("SELECT s.*, " + computedFullNameSql("s") + " AS full_name, u.username, u.email AS account_email FROM students s JOIN users u ON u.id=s.user_id WHERE s.user_id=?", uid);
                    } else if (role.equals("supervisor")) {
                        rs = query("SELECT t.*, " + computedFullNameSql("t") + " AS full_name, u.username, u.email AS account_email FROM teachers t JOIN users u ON u.id=t.user_id WHERE t.user_id=?", uid);
                    } else {
                        rs = query("SELECT id,username,email,role,created_at FROM users WHERE id=?", uid);
                    }
                    String json = resultToJson(rs);
                    if (json.startsWith("[{")) json = json.substring(1, json.length() - 1);
                    sendJson(ex, 200, json);
                } else if (ex.getRequestMethod().equals("PUT")) {
                    Map<String, String> b = parseJson(readBody(ex));
                    NameParts nameParts = resolveNameParts(b, b.getOrDefault("full_name", ""));
                    String fullName = nameParts.fullName;
                    String email    = normalizeEmail(b.getOrDefault("email", ""));
                    String phone    = normalize(b.getOrDefault("phone", ""));
                    String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                    String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                    String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                    String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                    String picture  = b.get("profile_picture");
                    require(isValidFullName(fullName), "Enter a valid full name");
                    requireValidEmail(email);
                    require(isValidPhone(phone), "Enter a valid phone number");
                    require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                    require(isLagunaProvince(province), "Province must be LAGUNA");
                    String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                    require(municipalityError == null, municipalityError);
                    String barangayError = getAddressComponentError("Barangay", barangay, 100);
                    require(barangayError == null, barangayError);
                    int age = calculateAgeFromBirthDate(birthDate);
                    if (!consumeVerifiedOtp(email, "profile_update")) {
                        sendJson(ex, 400, "{\"error\":\"Verify the OTP before saving profile changes\"}"); return;
                    }
                    if (hasReachedEmailUsageLimit(email, uid)) {
                        sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                    }
                    if (role.equals("staff")) {
                        String course = normalizeCourse(b.getOrDefault("course",""));
                        String yearLevel = normalize(b.getOrDefault("year_level","1"));
                        String section = normalizeSection(b.getOrDefault("section",""));
                        require(isValidCourse(course), "Enter a valid work area");
                        require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                        require(isValidSection(section), "Enter a valid shift group");
                        if (picture != null && !picture.isEmpty()) {
                            update("UPDATE students SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,course=?,year_level=?,section=?,profile_picture=? WHERE user_id=?",
                                blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, course, yearLevel, section, picture, uid);
                        } else {
                            update("UPDATE students SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,course=?,year_level=?,section=? WHERE user_id=?",
                                blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, course, yearLevel, section, uid);
                        }
                    } else if (role.equals("supervisor")) {
                        String department = normalize(b.getOrDefault("department",""));
                        String subject = normalize(b.getOrDefault("subject",""));
                        require(!department.isEmpty(), "Branch / department is required");
                        require(!subject.isEmpty(), "Assigned area is required");
                        if (picture != null && !picture.isEmpty()) {
                            update("UPDATE teachers SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,department=?,subject=?,profile_picture=? WHERE user_id=?",
                                blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, department, subject, picture, uid);
                        } else {
                            update("UPDATE teachers SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,department=?,subject=? WHERE user_id=?",
                                blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, department, subject, uid);
                        }
                    }
                    update("UPDATE users SET email=? WHERE id=?", email, uid);
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    if (auth != null && auth.startsWith("Bearer ")) {
                        Map<String, String> tok = tokenStore.get(auth.substring(7));
                        if (tok != null) tok.put("full_name", fullName);
                    }
                    sendJson(ex, 200, "{\"message\":\"Profile updated\"}");
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // ==========================================================
    // CRUD HANDLERS
    // ==========================================================

    static class StudentsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            if (!hasRole(user, "admin", "supervisor")) { sendJson(ex, 403, "{\"error\":\"Forbidden\"}"); return; }
            try {
                String method = ex.getRequestMethod();
                if (method.equals("GET")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String subjectId = normalize(qs.get("subject_id"));
                    boolean archivedOnly = isTruthy(qs.get("archived"));
                    if (!subjectId.isEmpty()) {
                        if (hasRole(user, "supervisor") && !subjectAccessibleToUser(user, subjectId)) {
                            sendJson(ex, 403, "{\"error\":\"Forbidden\"}"); return;
                        }
                        sendJson(ex, 200, resultToJson(query(
                            "SELECT st.*, " + computedFullNameSql("st") + " AS full_name FROM students st JOIN subjects sub ON sub.id=?" +
                            " WHERE (NULLIF(TRIM(COALESCE(sub.course,'')), '') IS NULL OR UPPER(sub.course)=UPPER(COALESCE(st.course,'')))" +
                            " AND (sub.year_level IS NULL OR sub.year_level=st.year_level)" +
                            " AND (NULLIF(TRIM(COALESCE(sub.section,'')), '') IS NULL OR UPPER(sub.section)=UPPER(COALESCE(st.section,'')))" +
                            " AND COALESCE(st.is_archived,FALSE)=FALSE" +
                            " ORDER BY " + fullNameOrderSql("st"),
                            subjectId
                        )));
                    } else {
                        sendJson(ex, 200, resultToJson(query(
                            "SELECT s.*, " + computedFullNameSql("s") + " AS full_name FROM students s WHERE COALESCE(s.is_archived,FALSE)=? ORDER BY " + fullNameOrderSql("s"),
                            archivedOnly
                        )));
                    }
                } else if (method.equals("POST")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only admin can add staff members\"}"); return; }
                    Map<String, String> b = parseJson(readBody(ex));
                    String username = normalize(b.get("username"));
                    String password = b.get("password");
                    NameParts nameParts = resolveNameParts(b, b.get("full_name"));
                    String fullName = nameParts.fullName;
                    String email = normalizeEmail(b.getOrDefault("email",""));
                    String phone = normalize(b.getOrDefault("phone",""));
                    String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                    String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                    String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                    String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                    String college = normalizeAddressComponent(b.getOrDefault("college", ""));
                    String course = normalizeCourse(b.getOrDefault("course",""));
                    String specialization = normalizeAddressComponent(b.getOrDefault("specialization", ""));
                    String yearLevel = normalize(b.getOrDefault("year_level","1"));
                    String section = normalizeSection(b.getOrDefault("section",""));
                    require(!username.isEmpty(), "Username is required");
                    require(isValidUsername(username), "Username must be 3-30 characters using letters, numbers, or underscores only");
                    require(password != null && password.length() >= 6, "Password must be at least 6 characters");
                    require(isValidFullName(fullName), "Enter a valid full name");
                    requireValidEmail(email);
                    require(isValidPhone(phone), "Enter a valid phone number");
                    require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                    require(isLagunaProvince(province), "Province must be LAGUNA");
                    String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                    require(municipalityError == null, municipalityError);
                    String barangayError = getAddressComponentError("Barangay", barangay, 100);
                    require(barangayError == null, barangayError);
                    require(!college.isEmpty(), "Branch / department is required");
                    require(isValidCourse(course), "Enter a valid work area");
                    require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                    require(isValidSection(section), "Enter a valid shift group");
                    require(!usernameExists(username, null), "Username already taken");
                    if (hasReachedEmailUsageLimit(email, null)) {
                        sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                    }
                    if (!consumeVerifiedOtp(email, "register")) {
                        sendJson(ex, 400, "{\"error\":\"Verify the OTP sent to the member email before creating the account\"}"); return;
                    }
                    int age = calculateAgeFromBirthDate(birthDate);
                    String sid = generateStudentId(yearLevel);
                    int uid = update("INSERT INTO users(username,password,email,role) VALUES(?,?,?,?)",
                        username, hashPassword(password), email, "staff");
                    update("INSERT INTO students(user_id,student_id,first_name,middle_name,last_name,suffix,email,phone,date_of_birth,age,province,municipality,barangay,college,course,specialization,year_level,section) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        uid, sid, blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email,
                        phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay,
                        college, course, blankToNull(specialization), yearLevel, section);
                    createNotificationForRoles(
                        "New staff member added by admin: " + fullName + " (" + sid + ").",
                        "warning",
                        "admin"
                    );
                    sendJson(ex, 201,
                        "{\"message\":\"Staff member added\"" +
                        ",\"student_id\":\"" + escapeJson(sid) + "\"}");
                } else if (method.equals("PUT")) {
                    Map<String, String> b = parseJson(readBody(ex));
                    String id = normalize(b.get("id"));
                    String action = normalize(b.getOrDefault("action", "")).toLowerCase(Locale.ROOT);
                    if (action.equals("restore")) {
                        require(isPositiveInteger(id), "Staff id is required");
                        String linkedUserId = findLinkedUserId("students", id);
                        restoreRecord("students", id);
                        restoreUserAccount(linkedUserId);
                        sendJson(ex, 200, "{\"message\":\"Staff restored\"}");
                        return;
                    }
                    NameParts nameParts = resolveNameParts(b, b.get("full_name"));
                    String fullName = nameParts.fullName;
                    String email = normalizeEmail(b.getOrDefault("email",""));
                    String phone = normalize(b.getOrDefault("phone",""));
                    String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                    String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                    String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                    String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                    String college = normalizeAddressComponent(b.getOrDefault("college", ""));
                    String course = normalizeCourse(b.getOrDefault("course",""));
                    String specialization = normalizeAddressComponent(b.getOrDefault("specialization", ""));
                    String yearLevel = normalize(b.getOrDefault("year_level","1"));
                    String section = normalizeSection(b.getOrDefault("section",""));
                    require(isPositiveInteger(id), "Staff id is required");
                    require(isValidFullName(fullName), "Enter a valid full name");
                    requireValidEmail(email);
                    require(isValidPhone(phone), "Enter a valid phone number");
                    require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                    require(isLagunaProvince(province), "Province must be LAGUNA");
                    String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                    require(municipalityError == null, municipalityError);
                    String barangayError = getAddressComponentError("Barangay", barangay, 100);
                    require(barangayError == null, barangayError);
                    require(!college.isEmpty(), "Branch / department is required");
                    require(isValidCourse(course), "Enter a valid team");
                    require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                    require(isValidSection(section), "Enter a valid shift group");
                    int age = calculateAgeFromBirthDate(birthDate);
                    ResultSet linked = query("SELECT user_id FROM students WHERE id=?", id);
                    if (!linked.next()) {
                        sendJson(ex, 404, "{\"error\":\"Staff not found\"}"); return;
                    }
                    String linkedUserId = linked.getString("user_id");
                    if (hasReachedEmailUsageLimit(email, linkedUserId)) {
                        sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                    }
                    update("UPDATE students SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,college=?,course=?,specialization=?,year_level=?,section=? WHERE id=?",
                        blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, college, course, blankToNull(specialization), yearLevel, section, id);
                    update("UPDATE users SET email=? WHERE id=?", email, linkedUserId);
                    sendJson(ex, 200, "{\"message\":\"Staff updated\"}");
                } else if (method.equals("DELETE")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String id = normalize(qs.get("id"));
                    boolean permanent = isTruthy(qs.get("permanent"));
                    require(isPositiveInteger(id), "Staff id is required");
                    if (permanent) {
                        permanentlyDeleteStudentRecord(id);
                        sendJson(ex, 200, "{\"message\":\"Staff permanently deleted\"}");
                    } else {
                        String linkedUser = findLinkedUserId("students", id);
                        archiveRecord("students", id);
                        archiveUserAccount(linkedUser);
                        sendJson(ex, 200, "{\"message\":\"Staff archived\"}");
                    }
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class TeachersHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Forbidden\"}"); return; }
            try {
                String method = ex.getRequestMethod();
                if (method.equals("GET")) {
                    boolean archivedOnly = isTruthy(parseQuery(ex.getRequestURI().getQuery()).get("archived"));
                    sendJson(ex, 200, resultToJson(query(
                        "SELECT t.*, " + computedFullNameSql("t") + " AS full_name FROM teachers t WHERE COALESCE(is_archived,FALSE)=? ORDER BY " + fullNameOrderSql("t"),
                        archivedOnly
                    )));
                } else if (method.equals("POST")) {
                    Map<String, String> b = parseJson(readBody(ex));
                    String username = normalize(b.get("username"));
                    String password = b.get("password");
                    NameParts nameParts = resolveNameParts(b, b.get("full_name"));
                    String fullName = nameParts.fullName;
                    String email = normalizeEmail(b.getOrDefault("email",""));
                    String phone = normalize(b.getOrDefault("phone",""));
                    String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                    String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                    String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                    String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                    String department = normalize(b.getOrDefault("department",""));
                    String subject = normalizeCourse(b.getOrDefault("subject",""));
                    String specialization = normalizeAddressComponent(b.getOrDefault("specialization", ""));
                    require(!username.isEmpty(), "Username is required");
                    require(isValidUsername(username), "Username must be 3-30 characters using letters, numbers, or underscores only");
                    require(password != null && password.length() >= 6, "Password must be at least 6 characters");
                    require(isValidFullName(fullName), "Enter a valid full name");
                    requireValidEmail(email);
                    require(isValidPhone(phone), "Enter a valid phone number");
                    require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                    require(isLagunaProvince(province), "Province must be LAGUNA");
                    String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                    require(municipalityError == null, municipalityError);
                    String barangayError = getAddressComponentError("Barangay", barangay, 100);
                    require(barangayError == null, barangayError);
                    require(!department.isEmpty(), "Branch / department is required");
                    require(isValidCourse(subject), "Enter a valid assigned area");
                    require(!usernameExists(username, null), "Username already taken");
                    if (hasReachedEmailUsageLimit(email, null)) {
                        sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                    }
                    if (!consumeVerifiedOtp(email, "register")) {
                        sendJson(ex, 400, "{\"error\":\"Verify the OTP sent to the member email before creating the account\"}"); return;
                    }
                    int age = calculateAgeFromBirthDate(birthDate);
                    int uid = update("INSERT INTO users(username,password,email,role) VALUES(?,?,?,?)",
                        username, hashPassword(password), email, "supervisor");
                    update("INSERT INTO teachers(user_id,first_name,middle_name,last_name,suffix,email,phone,date_of_birth,age,province,municipality,barangay,department,subject,specialization) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        uid, blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email,
                        phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay,
                        department, subject, blankToNull(specialization));
                    createNotificationForRoles(
                        "New supervisor member added by admin: " + fullName + ".",
                        "warning",
                        "admin"
                    );
                    sendJson(ex, 201, "{\"message\":\"Supervisor member added\"}");
                } else if (method.equals("PUT")) {
                    Map<String, String> b = parseJson(readBody(ex));
                    String id = normalize(b.get("id"));
                    String action = normalize(b.getOrDefault("action", "")).toLowerCase(Locale.ROOT);
                    if (action.equals("restore")) {
                        require(isPositiveInteger(id), "Supervisor id is required");
                        String linkedUserId = findLinkedUserId("teachers", id);
                        restoreRecord("teachers", id);
                        restoreUserAccount(linkedUserId);
                        sendJson(ex, 200, "{\"message\":\"Supervisor restored\"}");
                        return;
                    }
                    NameParts nameParts = resolveNameParts(b, b.get("full_name"));
                    String fullName = nameParts.fullName;
                    String email = normalizeEmail(b.getOrDefault("email",""));
                    String phone = normalize(b.getOrDefault("phone",""));
                    String birthDate = normalize(b.getOrDefault("date_of_birth", ""));
                    String province = normalizeAddressComponent(b.getOrDefault("province", "LAGUNA")).toUpperCase(Locale.ROOT);
                    String municipality = normalizeAddressComponent(b.getOrDefault("municipality", ""));
                    String barangay = normalizeAddressComponent(b.getOrDefault("barangay", ""));
                    String department = normalize(b.getOrDefault("department",""));
                    String subject = normalize(b.getOrDefault("subject",""));
                    String specialization = normalizeAddressComponent(b.getOrDefault("specialization", ""));
                    require(isPositiveInteger(id), "Supervisor id is required");
                    require(isValidFullName(fullName), "Enter a valid full name");
                    requireValidEmail(email);
                    require(isValidPhone(phone), "Enter a valid phone number");
                    require(isValidBirthDate(birthDate), getBirthDateValidationError(birthDate));
                    require(isLagunaProvince(province), "Province must be LAGUNA");
                    String municipalityError = getAddressComponentError("Municipality or city", municipality, 80);
                    require(municipalityError == null, municipalityError);
                    String barangayError = getAddressComponentError("Barangay", barangay, 100);
                    require(barangayError == null, barangayError);
                    require(!department.isEmpty(), "Department is required");
                    require(isValidCourse(subject), "Enter a valid assigned area");
                    int age = calculateAgeFromBirthDate(birthDate);
                    ResultSet linked = query("SELECT user_id FROM teachers WHERE id=?", id);
                    if (!linked.next()) {
                        sendJson(ex, 404, "{\"error\":\"Supervisor not found\"}"); return;
                    }
                    String linkedUserId = linked.getString("user_id");
                    if (hasReachedEmailUsageLimit(email, linkedUserId)) {
                        sendJson(ex, 409, "{\"error\":\"" + escapeJson(getEmailUsageLimitMessage()) + "\"}"); return;
                    }
                    update("UPDATE teachers SET first_name=?,middle_name=?,last_name=?,suffix=?,email=?,phone=?,date_of_birth=?,age=?,province=?,municipality=?,barangay=?,department=?,subject=?,specialization=? WHERE id=?",
                        blankToNull(nameParts.firstName), blankToNull(nameParts.middleName), blankToNull(nameParts.lastName), blankToNull(nameParts.suffix), email, phone, java.sql.Date.valueOf(birthDate), age, province, municipality, barangay, department, subject, blankToNull(specialization), id);
                    update("UPDATE users SET email=? WHERE id=?", email, linkedUserId);
                    sendJson(ex, 200, "{\"message\":\"Supervisor updated\"}");
                } else if (method.equals("DELETE")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String id = normalize(qs.get("id"));
                    boolean permanent = isTruthy(qs.get("permanent"));
                    require(isPositiveInteger(id), "Supervisor id is required");
                    if (permanent) {
                        permanentlyDeleteTeacherRecord(id);
                        sendJson(ex, 200, "{\"message\":\"Supervisor permanently deleted\"}");
                    } else {
                        String linkedUser = findLinkedUserId("teachers", id);
                        archiveRecord("teachers", id);
                        archiveUserAccount(linkedUser);
                        sendJson(ex, 200, "{\"message\":\"Supervisor archived\"}");
                    }
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class SubjectsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                String method = ex.getRequestMethod();
                if (method.equals("GET")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    boolean archivedOnly = isTruthy(qs.get("archived"));
                    StringBuilder sql = new StringBuilder(
                        "SELECT s.*, " + computedFullNameSql("t") + " AS teacher_name FROM subjects s" +
                        " LEFT JOIN teachers t ON t.id=s.teacher_id"
                    );
                    List<Object> params = new ArrayList<>();
                    sql.append(" WHERE COALESCE(s.is_archived,FALSE)=?");
                    params.add(archivedOnly);
                    if (hasRole(user, "supervisor")) {
                        sql.append(" AND s.teacher_id=?");
                        params.add(user.get("profile_id"));
                    } else if (hasRole(user, "staff")) {
                        Map<String, String> student = resolveStudent(user.get("profile_id"));
                        if (student == null) {
                            sendJson(ex, 200, "[]");
                            return;
                        }
                        sql.append(" AND (NULLIF(TRIM(COALESCE(s.course,'')), '') IS NULL OR UPPER(s.course)=?)");
                        sql.append(" AND (s.year_level IS NULL OR s.year_level=?)");
                        sql.append(" AND (NULLIF(TRIM(COALESCE(s.section,'')), '') IS NULL OR UPPER(s.section)=?)");
                        params.add(normalizeCourse(student.get("course")));
                        params.add(normalize(student.get("year_level")));
                        params.add(normalizeSection(student.get("section")));
                    }
                    sql.append(" ORDER BY s.code, s.name");
                    sendJson(ex, 200, resultToJson(query(sql.toString(), params.toArray())));
                } else if (method.equals("POST")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only managers can assign work areas\"}"); return; }
                    Map<String, String> b = parseJson(readBody(ex));
                    String code = normalizeSubjectCode(b.getOrDefault("code", ""));
                    String name = normalize(b.getOrDefault("name", "")).replaceAll("\\s+", " ");
                    String teacherId = normalize(b.getOrDefault("teacher_id", ""));
                    String college = normalize(b.getOrDefault("college", "")).replaceAll("\\s+", " ");
                    String course = normalizeCourse(b.getOrDefault("course", ""));
                    String specialization = normalize(b.getOrDefault("specialization", "")).replaceAll("\\s+", " ");
                    String yearLevel = normalize(b.getOrDefault("year_level", ""));
                    String section = normalizeSection(b.getOrDefault("section", ""));
                    int units = parseUnits(b.getOrDefault("units", "3"));

                    require(isValidSubjectCode(code), "Enter a valid work area code");
                    require(isValidSubjectName(name), "Enter a valid work area name");
                    require(isPositiveInteger(teacherId), "Supervisor is required");
                    require(recordExists("SELECT id FROM teachers WHERE id=? AND COALESCE(is_archived,FALSE)=FALSE LIMIT 1", teacherId), "Selected supervisor does not exist");
                    require(!college.isEmpty(), "Branch / department is required");
                    require(isValidCourse(course), "Enter a valid area type");
                    require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                    require(isValidSection(section), "Enter a valid shift group");
                    require(!recordExists("SELECT id FROM subjects WHERE UPPER(code)=UPPER(?) LIMIT 1", code), "Work area code already exists");

                    update(
                        "INSERT INTO subjects(code,name,teacher_id,units,college,course,specialization,year_level,section) VALUES(?,?,?,?,?,?,?,?,?)",
                        code, name, teacherId, units, college, course, specialization, yearLevel, section
                    );
                    sendJson(ex, 201, "{\"message\":\"Work area added\"}");
                } else if (method.equals("PUT")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only managers can update work area assignments\"}"); return; }
                    Map<String, String> b = parseJson(readBody(ex));
                    String id = normalize(b.get("id"));
                    String action = normalize(b.getOrDefault("action", "")).toLowerCase(Locale.ROOT);
                    if (action.equals("restore")) {
                        require(isPositiveInteger(id), "Work area id is required");
                        restoreRecord("subjects", id);
                        sendJson(ex, 200, "{\"message\":\"Work area restored\"}");
                        return;
                    }
                    String code = normalizeSubjectCode(b.getOrDefault("code", ""));
                    String name = normalize(b.getOrDefault("name", "")).replaceAll("\\s+", " ");
                    String teacherId = normalize(b.getOrDefault("teacher_id", ""));
                    String college = normalize(b.getOrDefault("college", "")).replaceAll("\\s+", " ");
                    String course = normalizeCourse(b.getOrDefault("course", ""));
                    String specialization = normalize(b.getOrDefault("specialization", "")).replaceAll("\\s+", " ");
                    String yearLevel = normalize(b.getOrDefault("year_level", ""));
                    String section = normalizeSection(b.getOrDefault("section", ""));
                    int units = parseUnits(b.getOrDefault("units", "3"));

                    require(isPositiveInteger(id), "Work area id is required");

                    require(isValidSubjectCode(code), "Enter a valid work area code");
                    require(isValidSubjectName(name), "Enter a valid work area name");
                    require(isPositiveInteger(teacherId), "Supervisor is required");
                    require(recordExists("SELECT id FROM teachers WHERE id=? AND COALESCE(is_archived,FALSE)=FALSE LIMIT 1", teacherId), "Selected supervisor does not exist");
                    require(!college.isEmpty(), "Branch / department is required");
                    require(isValidCourse(course), "Enter a valid area type");
                    require(isValidYearLevel(yearLevel), "Staff level must be Level 1 to Level 4");
                    require(isValidSection(section), "Enter a valid shift group");
                    require(!recordExists("SELECT id FROM subjects WHERE UPPER(code)=UPPER(?) AND id<>? LIMIT 1", code, id), "Work area code already exists");

                    update(
                        "UPDATE subjects SET code=?,name=?,teacher_id=?,units=?,college=?,course=?,specialization=?,year_level=?,section=? WHERE id=?",
                        code, name, teacherId, units, college, course, specialization, yearLevel, section, id
                    );
                    sendJson(ex, 200, "{\"message\":\"Work area updated\"}");
                } else if (method.equals("DELETE")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only managers can archive or delete work areas\"}"); return; }
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String id = normalize(qs.get("id"));
                    boolean permanent = isTruthy(qs.get("permanent"));
                    require(isPositiveInteger(id), "Work area id is required");
                    if (permanent) {
                        permanentlyDeleteSubjectRecord(id);
                        sendJson(ex, 200, "{\"message\":\"Work area permanently deleted\"}");
                    } else {
                        archiveRecord("subjects", id);
                        sendJson(ex, 200, "{\"message\":\"Work area archived\"}");
                    }
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    static class AttendanceHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                String method = ex.getRequestMethod();
                Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                if (method.equals("GET")) {
                    String date = qs.get("date");
                    String from = qs.get("from");
                    String to   = qs.get("to");
                    String sid  = resolveStudentIdForUser(user, qs.get("student_id"));
                    StringBuilder sql = new StringBuilder(
                        "SELECT a.id,a.student_id AS student_profile_id," + computedFullNameSql("s") + " AS full_name,s.student_id,sub.name AS subject_name,a.subject_id,a.date,a.time_in,a.status,a.method,a.latitude,a.longitude,a.location_address,a.remarks" +
                        " FROM attendance a JOIN students s ON s.id=a.student_id JOIN subjects sub ON sub.id=a.subject_id WHERE COALESCE(s.is_archived,FALSE)=FALSE AND COALESCE(sub.is_archived,FALSE)=FALSE"
                    );
                    List<Object> params = new ArrayList<>();
                    if (date != null && !date.isEmpty()) { sql.append(" AND a.date=?"); params.add(date); }
                    if (from != null && !from.isEmpty()) { sql.append(" AND a.date>=?"); params.add(from); }
                    if (to   != null && !to.isEmpty())   { sql.append(" AND a.date<=?"); params.add(to); }
                    if (sid  != null && !sid.isEmpty())  { sql.append(" AND a.student_id=?"); params.add(sid); }
                    sql.append(" ORDER BY a.date DESC, a.time_in DESC");
                    sendJson(ex, 200, resultToJson(query(sql.toString(), params.toArray())));
                } else if (method.equals("POST")) {
                    Map<String, String> b = parseJson(readBody(ex));
                    String studentRef = hasRole(user, "staff") ? user.get("profile_id") : b.get("student_id");
                    Map<String, String> student = resolveStudent(studentRef);
                    if (student == null) { sendJson(ex, 404, "{\"error\":\"Staff not found\"}"); return; }

                    String methodName = normalize(b.getOrDefault("method","manual")).toLowerCase(Locale.ROOT);
                    String subjectId = normalize(b.get("subject_id"));
                    String attendanceDate = normalize(b.get("date"));
                    String attendanceStatus = normalize(b.getOrDefault("status","absent")).toLowerCase(Locale.ROOT);
                    require(isAllowedAttendanceMethod(methodName), "Invalid attendance method");
                    if (hasRole(user, "admin") && !methodName.equals("manual")) {
                        sendJson(ex, 403, "{\"error\":\"Staff attendance is available on supervisor and staff accounts only\"}"); return;
                    }
                    require(isPositiveInteger(subjectId), "Work area is required");
                    require(recordExists("SELECT id FROM subjects WHERE id=? AND COALESCE(is_archived,FALSE)=FALSE LIMIT 1", subjectId), "Selected work area does not exist");
                    if (!hasRole(user, "admin") && !subjectAccessibleToUser(user, subjectId)) {
                        sendJson(ex, 403, "{\"error\":\"You cannot use that work area\"}"); return;
                    }
                    require(isValidDate(attendanceDate), "Attendance date must use YYYY-MM-DD format");
                    require(isAllowedAttendanceStatus(attendanceStatus), "Invalid attendance status");
                    if (hasRole(user, "staff") && !methodName.equals("geo")) {
                        sendJson(ex, 403, "{\"error\":\"Staff can only submit location attendance\"}"); return;
                    }
                    if (methodName.equals("geo")) {
                        String latitude = normalize(b.get("latitude"));
                        String longitude = normalize(b.get("longitude"));
                        require(!latitude.isEmpty() && !longitude.isEmpty(), "Live location is required for geo attendance");
                        if (!isWithinLagunaUniversityCampus(latitude, longitude)) {
                            notifyGeoBoundaryAttempt(
                                subjectId,
                                findSubjectName(subjectId),
                                student,
                                attendanceDate,
                                b.getOrDefault("time_in", currentTime()),
                                latitude,
                                longitude
                            );
                            sendJson(
                                ex,
                                400,
                                "{\"error\":\"You are outside the " + escapeJson(LU_CAMPUS_NAME) + " branch boundary in Sta. Cruz, Laguna\"}"
                            );
                            return;
                        }
                    }

                    upsertAttendance(
                        student.get("id"),
                        subjectId,
                        attendanceDate,
                        b.getOrDefault("time_in", currentTime()),
                        attendanceStatus,
                        methodName,
                        b.get("latitude"),
                        b.get("longitude"),
                        b.get("location_address"),
                        b.getOrDefault("remarks",""),
                        hasRole(user, "admin", "supervisor") ? user.get("id") : null
                    );
                    String subjectName = findSubjectName(subjectId);
                    String studentName = normalize(student.get("full_name"));
                    notifyAttendanceTracking(
                        subjectId,
                        subjectName,
                        student,
                        methodName,
                        attendanceDate,
                        b.getOrDefault("time_in", currentTime()),
                        b.get("latitude"),
                        b.get("longitude")
                    );
                    syncLowAttendanceNotifications();
                    sendJson(ex, 201, "{\"message\":\"Attendance recorded\"}");
                } else if (method.equals("PUT")) {
                    if (!hasRole(user, "admin", "supervisor")) { sendJson(ex, 403, "{\"error\":\"Forbidden\"}"); return; }
                    Map<String, String> b = parseJson(readBody(ex));
                    update("UPDATE attendance SET status=?,remarks=? WHERE id=?",
                        b.get("status"), b.getOrDefault("remarks",""), b.get("id"));
                    syncLowAttendanceNotifications();
                    sendJson(ex, 200, "{\"message\":\"Attendance updated\"}");
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class TimetableHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                String method = ex.getRequestMethod();
                if (method.equals("GET")) {
                    String matchingStaffWhere =
                        " FROM students st WHERE COALESCE(st.is_archived,FALSE)=FALSE" +
                        " AND (NULLIF(TRIM(COALESCE(s.course,'')), '') IS NULL OR UPPER(st.course)=UPPER(s.course))" +
                        " AND (s.year_level IS NULL OR st.year_level=s.year_level)" +
                        " AND (NULLIF(TRIM(COALESCE(s.section,'')), '') IS NULL OR UPPER(st.section)=UPPER(s.section))";
                    StringBuilder sql = new StringBuilder(
                        "SELECT t.*,s.name AS subject_name,s.code,s.course,s.specialization,s.year_level,s.section," + computedFullNameSql("te") + " AS teacher_name," +
                        " (SELECT COUNT(*)" + matchingStaffWhere + ") AS staff_count," +
                        " (SELECT GROUP_CONCAT(" + computedFullNameSql("st") + " ORDER BY " + fullNameOrderSql("st") + " SEPARATOR ', ')" + matchingStaffWhere + ") AS staff_names" +
                        " FROM timetable t JOIN subjects s ON s.id=t.subject_id" +
                        " LEFT JOIN teachers te ON te.id=s.teacher_id" +
                        " WHERE COALESCE(s.is_archived,FALSE)=FALSE AND COALESCE(te.is_archived,FALSE)=FALSE"
                    );
                    List<Object> params = new ArrayList<>();
                    if (hasRole(user, "supervisor")) {
                        sql.append(" AND s.teacher_id=?");
                        params.add(user.get("profile_id"));
                    } else if (hasRole(user, "staff")) {
                        Map<String, String> student = resolveStudent(user.get("profile_id"));
                        if (student == null) {
                            sendJson(ex, 200, "[]");
                            return;
                        }
                        sql.append(" AND (NULLIF(TRIM(COALESCE(s.course,'')), '') IS NULL OR UPPER(s.course)=?)");
                        sql.append(" AND (s.year_level IS NULL OR s.year_level=?)");
                        sql.append(" AND (NULLIF(TRIM(COALESCE(s.section,'')), '') IS NULL OR UPPER(s.section)=?)");
                        params.add(normalizeCourse(student.get("course")));
                        params.add(normalize(student.get("year_level")));
                        params.add(normalizeSection(student.get("section")));
                    }
                    sql.append(" ORDER BY FIELD(t.day_of_week,'Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'),t.start_time");
                    sendJson(ex, 200, resultToJson(query(sql.toString(), params.toArray())));
                } else if (method.equals("POST")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only admin can assign schedules\"}"); return; }
                    Map<String, String> b = parseJson(readBody(ex));
                    String subjectId = normalize(b.get("subject_id"));
                    String dayOfWeek = normalize(b.get("day_of_week"));
                    String startTime = normalize(b.get("start_time"));
                    String endTime = normalize(b.get("end_time"));
                    String room = normalize(b.getOrDefault("room", ""));
                    require(isPositiveInteger(subjectId), "Work area is required");
                    require(recordExists("SELECT id FROM subjects WHERE id=? AND COALESCE(is_archived,FALSE)=FALSE LIMIT 1", subjectId), "Selected work area does not exist");
                    require(isValidWeekday(dayOfWeek), "Select a valid day");
                    require(isValidTimeValue(startTime), "Start time is invalid");
                    require(isValidTimeValue(endTime), "End time is invalid");
                    require(startTime.compareTo(endTime) < 0, "End time must be after start time");
                    require(!room.isEmpty(), "Station / area is required");
                    String conflictMessage = findScheduleConflictMessage(dayOfWeek, startTime, endTime);
                    require(conflictMessage == null, conflictMessage);
                    update("INSERT INTO timetable(subject_id,day_of_week,start_time,end_time,room) VALUES(?,?,?,?,?)",
                        subjectId, dayOfWeek, startTime, endTime, room);
                    sendJson(ex, 201, "{\"message\":\"Schedule added\"}");
                } else if (method.equals("DELETE")) {
                    if (!hasRole(user, "admin")) { sendJson(ex, 403, "{\"error\":\"Only admin can remove schedules\"}"); return; }
                    String id = parseQuery(ex.getRequestURI().getQuery()).get("id");
                    require(isPositiveInteger(id), "Schedule id is required");
                    update("DELETE FROM timetable WHERE id=?", id);
                    sendJson(ex, 200, "{\"message\":\"Schedule deleted\"}");
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class ReportsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            if (!hasRole(user, "admin", "supervisor")) { sendJson(ex, 403, "{\"error\":\"Forbidden\"}"); return; }
            try {
                Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                String type = qs.getOrDefault("type", "attendance_summary");
                String from = qs.getOrDefault("from", "2000-01-01");
                String to   = qs.getOrDefault("to", "2099-12-31");
                ResultSet rs;
                if (type.equals("low_attendance")) {
                    rs = query(
                        "SELECT s.student_id, " + computedFullNameSql("s") + " AS full_name, s.email, s.course, s.specialization," +
                        " ROUND(SUM(a.status='present')*100.0/NULLIF(COUNT(a.id),0),1) AS attendance_rate" +
                        " FROM students s JOIN attendance a ON a.student_id=s.id" +
                        " WHERE a.date BETWEEN ? AND ? AND COALESCE(s.is_archived,FALSE)=FALSE" +
                        " GROUP BY s.id HAVING attendance_rate < 75 ORDER BY attendance_rate",
                        from, to
                    );
                } else {
                    rs = query(
                        "SELECT s.student_id, " + computedFullNameSql("s") + " AS full_name, s.course, s.specialization, s.section," +
                        " COUNT(a.id) AS total_days," +
                        " COALESCE(SUM(CASE WHEN a.status='present' THEN 1 ELSE 0 END),0) AS present_days," +
                        " COALESCE(SUM(CASE WHEN a.status='absent' THEN 1 ELSE 0 END),0)  AS absent_days," +
                        " COALESCE(SUM(CASE WHEN a.status='late' THEN 1 ELSE 0 END),0)    AS late_days," +
                        " COALESCE(ROUND(SUM(CASE WHEN a.status='present' THEN 1 ELSE 0 END)*100.0/NULLIF(COUNT(a.id),0),1),0) AS attendance_rate" +
                        " FROM students s LEFT JOIN attendance a ON a.student_id=s.id AND a.date BETWEEN ? AND ?" +
                        " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
                        " GROUP BY s.id ORDER BY " + fullNameOrderSql("s"),
                        from, to
                    );
                }
                sendJson(ex, 200, resultToJson(rs));
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class DashboardHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                String[] range = resolveDashboardDateRange(qs);
                String from = range[0];
                String to = range[1];
                syncLowAttendanceNotifications();
                ResultSet stats = query(
                    "SELECT (SELECT COUNT(*) FROM students WHERE COALESCE(is_archived,FALSE)=FALSE) AS total_students," +
                    " (SELECT COUNT(*) FROM teachers WHERE COALESCE(is_archived,FALSE)=FALSE) AS total_teachers," +
                    " (SELECT COUNT(*) FROM attendance a JOIN students s ON s.id=a.student_id WHERE a.date BETWEEN ? AND ? AND a.status='present' AND COALESCE(s.is_archived,FALSE)=FALSE) AS present_today," +
                    " (SELECT COUNT(*) FROM attendance a JOIN students s ON s.id=a.student_id WHERE a.date BETWEEN ? AND ? AND a.status='absent' AND COALESCE(s.is_archived,FALSE)=FALSE)  AS absent_today",
                    from, to, from, to);
                ResultSet daily = query(
                    "SELECT DATE_FORMAT(date,'%a') AS day, SUM(status='present') AS present, SUM(status='absent') AS absent" +
                    " FROM attendance a JOIN students s ON s.id=a.student_id" +
                    " WHERE a.date BETWEEN ? AND ? AND COALESCE(s.is_archived,FALSE)=FALSE GROUP BY a.date ORDER BY a.date",
                    from, to);
                ResultSet weekly = query(
                    "SELECT DATE_FORMAT(MIN(date),'%b %d') AS label," +
                    " ROUND(AVG(status='present')*100,1) AS attendance_rate," +
                    " ROUND(AVG(status='absent')*100,1) AS absent_rate" +
                    " FROM attendance a JOIN students s ON s.id=a.student_id" +
                    " WHERE a.date BETWEEN ? AND ? AND COALESCE(s.is_archived,FALSE)=FALSE" +
                    " GROUP BY YEARWEEK(a.date,1) ORDER BY YEARWEEK(a.date,1)",
                    from, to);
                ResultSet monthly = query(
                    "SELECT DATE_FORMAT(MIN(date),'%b %Y') AS label," +
                    " ROUND(AVG(status='present')*100,1) AS attendance_rate," +
                    " ROUND(AVG(status='absent')*100,1) AS absent_rate" +
                    " FROM attendance a JOIN students s ON s.id=a.student_id" +
                    " WHERE a.date BETWEEN ? AND ? AND COALESCE(s.is_archived,FALSE)=FALSE" +
                    " GROUP BY YEAR(a.date), MONTH(a.date) ORDER BY YEAR(a.date), MONTH(a.date)",
                    from, to);
                ResultSet performance = query(
                    "SELECT " + computedFullNameSql("s") + " AS full_name," +
                    " COALESCE(ROUND(SUM(CASE WHEN a.status='present' THEN 1 ELSE 0 END)*100.0/NULLIF(COUNT(a.id),0),1),0) AS attendance_rate" +
                    " FROM students s LEFT JOIN attendance a ON a.student_id=s.id AND a.date BETWEEN ? AND ?" +
                    " WHERE COALESCE(s.is_archived,FALSE)=FALSE" +
                    " GROUP BY s.id ORDER BY attendance_rate DESC, " + fullNameOrderSql("s") + " LIMIT 8",
                    from, to);
                ResultSet alerts = query(
                    "SELECT " + computedFullNameSql("s") + " AS full_name, ROUND(SUM(a.status='present')*100.0/NULLIF(COUNT(a.id),0),1) AS rate" +
                    " FROM students s JOIN attendance a ON a.student_id=s.id" +
                    " WHERE a.date BETWEEN ? AND ? AND COALESCE(s.is_archived,FALSE)=FALSE" +
                    " GROUP BY s.id HAVING rate < 75 ORDER BY rate",
                    from, to);
                sendJson(ex, 200,
                    "{\"stats\":" + resultToJson(stats) +
                    ",\"meta\":{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}" +
                    ",\"daily\":" + resultToJson(daily) +
                    ",\"weekly\":" + resultToJson(weekly) +
                    ",\"monthly\":" + resultToJson(monthly) +
                    ",\"performance\":" + resultToJson(performance) +
                    ",\"alerts\":" + resultToJson(alerts) + "}");
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class QRHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                if (ex.getRequestMethod().equals("GET")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String ref = qs.get("student_id");
                    if (hasRole(user, "staff")) ref = user.get("profile_id");
                    Map<String, String> student = resolveStudent(ref);
                    if (student == null) { sendJson(ex, 404, "{\"error\":\"Staff not found\"}"); return; }
                    String qrData = buildStudentQrPayload(student);
                    sendJson(ex, 200,
                        "{\"student_ref\":\"" + escapeJson(student.get("id")) + "\"" +
                        ",\"student_id\":\"" + escapeJson(student.get("student_id")) + "\"" +
                        ",\"name\":\"" + escapeJson(student.get("full_name")) + "\"" +
                        ",\"qr_data\":\"" + escapeJson(qrData) + "\"" +
                        ",\"is_static\":true" +
                        ",\"valid_for_seconds\":0}");
                    return;
                }

                if (!hasRole(user, "supervisor")) { sendJson(ex, 403, "{\"error\":\"QR scanning is available on supervisor accounts only\"}"); return; }
                Map<String, String> b = parseJson(readBody(ex));
                String qrData = b.get("qr_data");
                if (qrData == null || qrData.isEmpty()) { sendJson(ex, 400, "{\"error\":\"qr_data required\"}"); return; }

                Map<String, String> student = parseStudentQrPayload(qrData);
                String requestedSubjectId = normalize(b.get("subject_id"));
                String subjectId = resolveQrSubjectId(user, student, requestedSubjectId);
                require(isPositiveInteger(subjectId), "No matching work area found for this staff member");
                require(recordExists("SELECT id FROM subjects WHERE id=? LIMIT 1", subjectId), "Selected work area does not exist");
                if (!requestedSubjectId.isEmpty() && !hasRole(user, "admin") && !subjectAccessibleToUser(user, subjectId)) {
                    sendJson(ex, 403, "{\"error\":\"You cannot use that work area\"}"); return;
                }
                String attendanceDate = b.getOrDefault("date", currentDate());
                String attendanceTime = b.getOrDefault("time_in", currentTime());
                require(isValidDate(attendanceDate), "Attendance date must use YYYY-MM-DD format");
                require(isValidTimeValue(attendanceTime), "Attendance time is invalid");
                if (hasQrAttendanceForDate(student.get("id"), attendanceDate)) {
                    sendJson(ex, 409,
                        "{\"error\":\"This staff member has already been scanned for " + escapeJson(attendanceDate) + ". Only one QR scan is allowed per day.\"," +
                        "\"name\":\"" + escapeJson(student.get("full_name")) + "\"," +
                        "\"student_id\":\"" + escapeJson(student.get("student_id")) + "\"}");
                    return;
                }

                String latitude = normalize(b.get("latitude"));
                String longitude = normalize(b.get("longitude"));
                String locationAddress = normalize(b.get("location_address"));

                upsertAttendance(
                    student.get("id"),
                    subjectId,
                    attendanceDate,
                    attendanceTime,
                    "present",
                    "qr",
                    latitude,
                    longitude,
                    locationAddress,
                    "",
                    user.get("id")
                );
                String subjectName = findSubjectName(subjectId);
                notifyAttendanceTracking(
                    subjectId,
                    subjectName,
                    student,
                    "qr",
                    attendanceDate,
                    attendanceTime,
                    latitude,
                    longitude
                );
                syncLowAttendanceNotifications();
                sendJson(ex, 200,
                    "{\"message\":\"Attendance marked\",\"name\":\"" + student.get("full_name").replace("\"","\\\"") + "\"" +
                    ",\"student_id\":\"" + student.get("student_id") + "\"" +
                    ",\"subject_id\":\"" + escapeJson(subjectId) + "\"" +
                    ",\"subject_name\":\"" + escapeJson(subjectName) + "\"" +
                    ",\"date\":\"" + attendanceDate + "\"" +
                    ",\"time_in\":\"" + attendanceTime + "\"" +
                    ",\"latitude\":\"" + escapeJson(latitude) + "\"" +
                    ",\"longitude\":\"" + escapeJson(longitude) + "\"" +
                    ",\"location_address\":\"" + escapeJson(locationAddress) + "\"}");
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

    static class NotifyHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (ex.getRequestMethod().equals("OPTIONS")) { sendJson(ex, 200, "{}"); return; }
            Map<String, String> user = validateToken(ex);
            if (user == null) { sendJson(ex, 401, "{\"error\":\"Unauthorized\"}"); return; }
            try {
                syncLowAttendanceNotifications();
                if (ex.getRequestMethod().equals("GET")) {
                    Map<String, String> qs = parseQuery(ex.getRequestURI().getQuery());
                    String detail = normalize(qs.get("detail")).toLowerCase(Locale.ROOT);
                    if (detail.equals("low_attendance")) {
                        if (!hasRole(user, "staff")) {
                            sendJson(ex, 403, "{\"error\":\"Low-attendance detail view is available on staff accounts only\"}");
                            return;
                        }
                        sendJson(ex, 200, buildStudentLowAttendanceDetailJson(user.get("profile_id")));
                        return;
                    }
                    String from = normalize(qs.get("from"));
                    String to = normalize(qs.get("to"));
                    ResultSet rs;
                    if (!from.isEmpty() || !to.isEmpty()) {
                        String[] range = resolveDashboardDateRange(qs);
                        rs = query(
                            "SELECT * FROM notifications WHERE user_id=? AND DATE(created_at) BETWEEN ? AND ? ORDER BY created_at DESC",
                            user.get("id"), range[0], range[1]
                        );
                    } else {
                        rs = query("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC", user.get("id"));
                    }
                    sendJson(ex, 200, resultToJson(rs));
                } else if (ex.getRequestMethod().equals("PUT")) {
                    Map<String, String> body = parseJson(readBody(ex));
                    String id = normalize(body.get("id"));
                    if (isPositiveInteger(id)) {
                        update("UPDATE notifications SET is_read=TRUE WHERE user_id=? AND id=?", user.get("id"), id);
                        sendJson(ex, 200, "{\"message\":\"Notification marked as read\"}");
                    } else {
                        update("UPDATE notifications SET is_read=TRUE WHERE user_id=?", user.get("id"));
                        sendJson(ex, 200, "{\"message\":\"Marked all read\"}");
                    }
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) { sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}"); }
        }
    }

}
