package pleos.nextkey.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import pleos.nextkey.common.ApiError;
import pleos.nextkey.common.Json;

final class CoreData {
    private static final SecureRandom RANDOM = new SecureRandom();
    private CoreData() {}

    static Map<String,Object> first(JdbcTemplate db, String sql, Object... args) {
        return db.queryForList(sql, args).stream().findFirst().orElse(Map.of());
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static boolean matches(String value, String digest) {
        return value != null && value.length()==43 && digest != null && MessageDigest.isEqual(
                hash(value).getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII));
    }
    static LocalDateTime sql(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    static Instant instant(Object value) {
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalArgumentException("Expected database timestamp");
    }
    static String string(Map<String,Object> row,String key) { return (String) row.get(key); }
    static Map<String,Object> json(Object value) {
        if (value == null) return Map.of();
        if (value instanceof byte[] bytes) return Json.read(new String(bytes,StandardCharsets.UTF_8));
        return Json.read(value.toString());
    }
    static ApiError fail(int status,String code) { return new ApiError(status,code); }
}
