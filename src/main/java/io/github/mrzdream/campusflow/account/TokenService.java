package io.github.mrzdream.campusflow.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private static final Duration LIFETIME = Duration.ofHours(1);

    private final JwtEncoder encoder;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public AccessToken issue(long userId, String role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LIFETIME);
        var claims = JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER)
                .subject(Long.toString(userId)).issuedAt(now).expiresAt(expiresAt).claim("role", role).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, "Bearer", expiresAt);
    }

    public record AccessToken(String accessToken, String tokenType, Instant expiresAt) { }
}
