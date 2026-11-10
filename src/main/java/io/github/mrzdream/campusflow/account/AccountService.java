package io.github.mrzdream.campusflow.account;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.github.mrzdream.campusflow.web.ApiFailure;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String dummyHash;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords, TokenService tokens) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Account register(String username, String password) {
        String normalized = normalize(username);
        try {
            jdbc.update("INSERT INTO campus_users (username, password_hash) VALUES (?, ?)",
                    normalized, passwords.encode(checkedPassword(password)));
        } catch (DuplicateKeyException exception) {
            throw ApiFailure.conflict("USERNAME_TAKEN", "That username is already registered.");
        }
        User row = find(normalized).orElseThrow();
        return new Account(row.id(), normalized, row.role());
    }

    public TokenService.AccessToken login(String username, String password) {
        var user = find(normalize(username));
        String hash = user.map(User::hash).orElse(dummyHash);
        if (!passwords.matches(checkedPassword(password), hash) || user.isEmpty()) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect.");
        }
        User row = user.orElseThrow();
        return tokens.issue(row.id(), row.role());
    }

    void ensureAdmin(String username, String password) {
        String normalized = normalize(username);
        checkedPassword(password);
        var existing = find(normalized);
        if (existing.isEmpty()) {
            try {
                jdbc.update("INSERT INTO campus_users (username, password_hash, role) VALUES (?, ?, 'ADMIN')",
                        normalized, passwords.encode(password));
                return;
            } catch (DuplicateKeyException concurrentInsert) {
                existing = find(normalized);
            }
        }
        if (existing.isEmpty() || !"ADMIN".equals(existing.get().role())) {
            throw new IllegalStateException("Configured administrator username belongs to a non-administrator.");
        }
    }

    private Optional<User> find(String username) {
        return jdbc.query("SELECT id, password_hash, role FROM campus_users WHERE username = ?",
                (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3)), username).stream().findFirst();
    }

    private static String normalize(String username) {
        if (!username.matches("[A-Za-z0-9._-]{3,40}")) {
            throw ApiFailure.invalid("INVALID_USERNAME", "Use 3 to 40 letters, digits, dots, underscores or hyphens.");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private static String checkedPassword(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || bytes > 72) {
            throw ApiFailure.invalid("INVALID_PASSWORD", "Use at least 12 characters and at most 72 UTF-8 bytes.");
        }
        return password;
    }

    private record User(long id, String hash, String role) { }
    public record Account(long id, String username, String role) { }
}
