import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ValidationUtils {
    static final int MAX_EMAIL_USAGE_COUNT = 1;

    private static final Pattern EMAIL_LOCAL_PATTERN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._%+-]{0,62}[A-Za-z0-9])?$");
    private static final Pattern DOMAIN_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
    private static final Pattern TOP_LEVEL_LABEL_PATTERN = Pattern.compile("^[A-Za-z]{2,10}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,30}$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z .,'-]{2,80}$");
    private static final Pattern ADDRESS_COMPONENT_PATTERN = Pattern.compile("^[A-Za-zÀ-ÿ0-9 .,'()/-]{2,100}$");
    private static final Pattern COURSE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9 .,&()/-]{1,119}$");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 .()/-]{0,39}$");
    private static final Pattern SUBJECT_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{1,19}$");
    private static final Pattern SUBJECT_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9 .,&()'/-]{2,60}$");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Set<String> ALLOWED_PUBLIC_EMAIL_DOMAINS = new HashSet<>(Arrays.asList(
        "gmail.com",
        "outlook.com",
        "hotmail.com",
        "yahoo.com",
        "icloud.com",
        "proton.me",
        "protonmail.com",
        "zoho.com",
        "gmx.com",
        "aol.com"
    ));
    private static final Set<String> ALLOWED_GENERAL_EMAIL_EXTENSIONS = new HashSet<>(Arrays.asList("com", "net", "org", "info", "biz"));
    private static final Set<String> ALLOWED_COUNTRY_EMAIL_EXTENSIONS = new HashSet<>(Arrays.asList("ph", "us", "uk", "au", "ca", "jp", "kr", "sg"));
    private static final Set<String> ALLOWED_INSTITUTIONAL_EMAIL_EXTENSIONS = new HashSet<>(Arrays.asList("edu", "gov", "mil"));
    private static final Set<String> ALLOWED_SECOND_LEVEL_COUNTRY_EXTENSIONS = new HashSet<>(Arrays.asList("com", "net", "org", "info", "biz", "edu", "gov"));
    private static final Set<String> OTP_PURPOSES = new HashSet<>(Arrays.asList("register", "profile_update", "reset_password"));
    private static final Set<String> ATTENDANCE_STATUSES = new HashSet<>(Arrays.asList("present", "absent", "late", "excused"));
    private static final Set<String> ATTENDANCE_METHODS = new HashSet<>(Arrays.asList("manual", "qr", "geo"));

    private ValidationUtils() {}

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizeEmail(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    static String normalizeCourse(String value) {
        return normalize(value).replaceAll("\\s+", " ");
    }

    static String normalizeSection(String value) {
        return normalize(value).replaceAll("\\s+", " ");
    }

    static String normalizeSubjectCode(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    static String normalizeAddressComponent(String value) {
        return normalize(value).replaceAll("\\s+", " ");
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String toNameCase(String value) {
        String normalized = normalize(value).replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetter(ch)) {
                sb.append(capitalizeNext ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                capitalizeNext = false;
            } else {
                sb.append(ch);
                capitalizeNext = Character.isWhitespace(ch) || ch == '-' || ch == '\'';
            }
        }
        return sb.toString();
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static boolean hasAllowedInstitutionalEmailSuffix(String[] labels) {
        if (labels.length < 3 || labels.length > 4) return false;

        String last = labels[labels.length - 1];
        String secondLast = labels.length >= 2 ? labels[labels.length - 2] : "";

        if (ALLOWED_INSTITUTIONAL_EMAIL_EXTENSIONS.contains(last)) {
            return true;
        }

        if (ALLOWED_COUNTRY_EMAIL_EXTENSIONS.contains(last)) {
            return ALLOWED_SECOND_LEVEL_COUNTRY_EXTENSIONS.contains(secondLast);
        }

        return ALLOWED_GENERAL_EMAIL_EXTENSIONS.contains(last);
    }

    private static boolean isAcceptedEmailDomain(String domainPart, String[] labels) {
        return ALLOWED_PUBLIC_EMAIL_DOMAINS.contains(domainPart) || hasAllowedInstitutionalEmailSuffix(labels);
    }

    static String getEmailValidationError(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty()) return "Email is required";
        if (normalized.length() > 120) return "Email is too long";
        if (normalized.contains("..")) return "Email cannot contain consecutive dots";

        int atIndex = normalized.indexOf('@');
        if (atIndex <= 0 || atIndex != normalized.lastIndexOf('@') || atIndex == normalized.length() - 1) {
            return "Email must contain one @ with a valid domain";
        }

        String localPart = normalized.substring(0, atIndex);
        String domainPart = normalized.substring(atIndex + 1);
        if (localPart.length() > 64) return "Email name before @ is too long";
        if (!EMAIL_LOCAL_PATTERN.matcher(localPart).matches()) {
            return "Email name before @ contains invalid characters";
        }

        String[] labels = domainPart.split("\\.");
        if (labels.length < 2 || labels.length > 4) {
            return "Use a valid email like name@yahoo.com, name@outlook.com, or name@lu.edu.ph";
        }

        for (String label : labels) {
            if (!DOMAIN_LABEL_PATTERN.matcher(label).matches()) {
                return "Email domain contains invalid characters";
            }
        }

        if (!TOP_LEVEL_LABEL_PATTERN.matcher(labels[labels.length - 1]).matches()) {
            return "Email extension must use letters only";
        }

        if (!isAcceptedEmailDomain(domainPart, labels)) {
            return "Use Gmail, Outlook, Hotmail, Yahoo, iCloud, Proton, Zoho, GMX, AOL, or a school email like name@campus.edu.ph";
        }

        return null;
    }

    static boolean isValidEmail(String email) {
        return getEmailValidationError(email) == null;
    }

    static boolean isValidUsername(String username) {
        return USERNAME_PATTERN.matcher(normalize(username)).matches();
    }

    static boolean isValidFullName(String fullName) {
        return NAME_PATTERN.matcher(toNameCase(fullName)).matches();
    }

    static boolean isValidPhone(String phone) {
        if (isBlank(phone)) return true;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() >= 10 && digits.length() <= 15;
    }

    static boolean isValidCourse(String course) {
        return COURSE_PATTERN.matcher(normalizeCourse(course)).matches();
    }

    static boolean isValidSection(String section) {
        return SECTION_PATTERN.matcher(normalizeSection(section)).matches();
    }

    static boolean isValidYearLevel(String yearLevel) {
        String normalized = normalize(yearLevel);
        return normalized.equals("1") || normalized.equals("2") || normalized.equals("3") || normalized.equals("4");
    }

    static boolean isValidDate(String date) {
        return DATE_PATTERN.matcher(normalize(date)).matches();
    }

    static String getBirthDateValidationError(String birthDate) {
        String normalized = normalize(birthDate);
        if (normalized.isEmpty()) return "Date of birth is required";
        if (!isValidDate(normalized)) return "Date of birth must use YYYY-MM-DD format";

        LocalDate dob;
        try {
            dob = LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            return "Enter a valid date of birth";
        }

        int age = Period.between(dob, LocalDate.now()).getYears();
        if (age < 15 || age > 80) return "Age must be between 15 and 80 years old";
        return null;
    }

    static boolean isValidBirthDate(String birthDate) {
        return getBirthDateValidationError(birthDate) == null;
    }

    static int calculateAgeFromBirthDate(String birthDate) {
        String error = getBirthDateValidationError(birthDate);
        if (error != null) throw new IllegalArgumentException(error);
        return Period.between(LocalDate.parse(normalize(birthDate)), LocalDate.now()).getYears();
    }

    static boolean isLagunaProvince(String province) {
        return "LAGUNA".equalsIgnoreCase(normalize(province));
    }

    static String getAddressComponentError(String label, String value, int maxLength) {
        String normalized = normalizeAddressComponent(value);
        if (normalized.isEmpty()) return label + " is required";
        if (normalized.length() > maxLength) return label + " is too long";
        if (!ADDRESS_COMPONENT_PATTERN.matcher(normalized).matches()) {
            return "Enter a valid " + label.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static boolean isPositiveInteger(String value) {
        return normalize(value).matches("^\\d+$");
    }

    static boolean isValidStudentNumber(String studentNumber) {
        return normalize(studentNumber).matches("^\\d{3,4}-\\d{4}$");
    }

    static boolean isValidSubjectCode(String subjectCode) {
        return SUBJECT_CODE_PATTERN.matcher(normalizeSubjectCode(subjectCode)).matches();
    }

    static boolean isValidSubjectName(String subjectName) {
        return SUBJECT_NAME_PATTERN.matcher(normalize(subjectName).replaceAll("\\s+", " ")).matches();
    }

    static int parseUnits(String unitsValue) {
        String normalized = normalize(unitsValue);
        if (!normalized.matches("^\\d+$")) {
            throw new IllegalArgumentException("Units must be a whole number");
        }
        int units = Integer.parseInt(normalized);
        if (units < 1 || units > 6) {
            throw new IllegalArgumentException("Units must be between 1 and 6");
        }
        return units;
    }

    static boolean isValidOtpPurpose(String purpose) {
        return OTP_PURPOSES.contains(normalize(purpose).toLowerCase(Locale.ROOT));
    }

    static boolean isAllowedSelfRegisterRole(String role) {
        String normalized = normalize(role).toLowerCase(Locale.ROOT);
        return normalized.equals("staff") || normalized.equals("supervisor");
    }

    static boolean isAllowedAttendanceStatus(String status) {
        return ATTENDANCE_STATUSES.contains(normalize(status).toLowerCase(Locale.ROOT));
    }

    static boolean isAllowedAttendanceMethod(String method) {
        return ATTENDANCE_METHODS.contains(normalize(method).toLowerCase(Locale.ROOT));
    }

    static boolean recordExists(String sql, Object... params) throws SQLException {
        ResultSet rs = App.query(sql, params);
        return rs.next();
    }

    static boolean usernameExists(String username, String exceptUserId) throws SQLException {
        String normalized = normalize(username).toLowerCase(Locale.ROOT);
        if (isBlank(exceptUserId)) {
            return recordExists("SELECT id FROM users WHERE LOWER(username)=? LIMIT 1", normalized);
        }
        return recordExists("SELECT id FROM users WHERE LOWER(username)=? AND id<>? LIMIT 1", normalized, exceptUserId);
    }

    static boolean emailExists(String email, String exceptUserId) throws SQLException {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty()) return false;
        if (isBlank(exceptUserId)) {
            return recordExists("SELECT id FROM users WHERE LOWER(email)=? LIMIT 1", normalized);
        }
        return recordExists("SELECT id FROM users WHERE LOWER(email)=? AND id<>? LIMIT 1", normalized, exceptUserId);
    }

    static int getEmailUsageCount(String email, String exceptUserId) throws SQLException {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty()) return 0;

        ResultSet rs;
        if (isBlank(exceptUserId)) {
            rs = App.query("SELECT COUNT(*) AS total FROM users WHERE LOWER(email)=?", normalized);
        } else {
            rs = App.query("SELECT COUNT(*) AS total FROM users WHERE LOWER(email)=? AND id<>?", normalized, exceptUserId);
        }
        return rs.next() ? rs.getInt("total") : 0;
    }

    static boolean hasReachedEmailUsageLimit(String email, String exceptUserId) throws SQLException {
        return getEmailUsageCount(email, exceptUserId) >= MAX_EMAIL_USAGE_COUNT;
    }

}
