package com.geoji.app.auth.service;

import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;

@Service
public class TokenService {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final StringRedisTemplate redis;
    private final UserRepository users;

    public TokenService(@Value("${geoji.jwt.secret-base64}") String keyBase64,
                        @Value("${geoji.jwt.issuer}") String issuer, StringRedisTemplate redis, UserRepository users) {
        byte[] key;
        try { key = Base64.getDecoder().decode(keyBase64); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", e); }
        if (key.length < 32) throw new IllegalStateException("JWT_SECRET_BASE64 must contain at least 32 bytes");
        var secret = new SecretKeySpec(key, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(secret));
        this.decoder = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
        this.issuer = issuer;
        this.redis = redis;
        this.users = users;
    }

    public record Tokens(String accessToken, String refreshToken, long expiresIn) {}

    public Tokens issue(User user) {
        String access = create(user, "access", ACCESS_TTL);
        String refresh = create(user, "refresh", REFRESH_TTL);
        Jwt jwt = decoder.decode(refresh);
        redis.opsForValue().set("auth:refresh:" + jwt.getId(), user.getId().toString(), REFRESH_TTL);
        return new Tokens(access, refresh, ACCESS_TTL.toSeconds());
    }

    private String create(User user, String type, Duration ttl) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plus(ttl))
                .claim("type", type).claim("version", user.getAuthVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public Jwt verify(String value, String type) {
        try {
            Jwt jwt = decoder.decode(value);
            if (!issuer.equals(jwt.getClaimAsString("iss")) || !type.equals(jwt.getClaimAsString("type"))) throw invalid();
            return jwt;
        } catch (JwtException | NullPointerException e) { throw invalid(); }
    }

    public User authenticated(String accessToken) {
        Jwt jwt = verify(accessToken, "access");
        if (Boolean.TRUE.equals(redis.hasKey("auth:blacklist:" + jwt.getId()))) throw invalid();
        User user = users.findById(UUID.fromString(jwt.getSubject())).orElseThrow(this::invalid);
        if (user.getStatus() != User.Status.ACTIVE || !versionMatches(jwt, user)) throw invalid();
        return user;
    }

    public Tokens rotate(String refreshToken) {
        Jwt jwt = verify(refreshToken, "refresh");
        String key = "auth:refresh:" + jwt.getId();
        String owner = redis.opsForValue().getAndDelete(key);
        if (owner == null || !owner.equals(jwt.getSubject())) throw invalid();
        User user = users.findById(UUID.fromString(owner)).orElseThrow(this::invalid);
        if (user.getStatus() != User.Status.ACTIVE || !versionMatches(jwt, user)) throw invalid();
        return issue(user);
    }

    public void logout(String accessToken, String refreshToken) {
        Jwt access = verify(accessToken, "access");
        Duration remaining = Duration.between(Instant.now(), access.getExpiresAt());
        if (!remaining.isNegative() && !remaining.isZero()) redis.opsForValue().set("auth:blacklist:" + access.getId(), "1", remaining);
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Jwt refresh = verify(refreshToken, "refresh");
                if (access.getSubject().equals(refresh.getSubject())) redis.delete("auth:refresh:" + refresh.getId());
            } catch (ApiException ignored) { /* Access token still revoked. */ }
        }
    }

    private ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증이 유효하지 않습니다."); }
    private static boolean versionMatches(Jwt jwt, User user) {
        Object value = jwt.getClaim("version");
        return value instanceof Number number && number.intValue() == user.getAuthVersion();
    }
}
