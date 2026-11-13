package com.geoji.app.auth.controller;

import com.geoji.app.auth.service.TokenService;
import com.geoji.app.auth.repository.OAuthAccountRepository;
import com.geoji.app.auth.entity.OAuthAccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoji.app.global.*;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import com.geoji.app.user.controller.UserController;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jwt.*;

@RestController @RequestMapping("/api/v1/auth/oauth")
public class OAuthController {
    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final OAuthAccountRepository accounts;
    private final TokenService tokens;
    private final RestClient http = RestClient.create();
    private final ObjectMapper json;
    @Value("${geoji.oauth.callback-base-url}") private String callbackBase;
    @Value("${geoji.oauth.success-redirect-url}") private String successRedirect;
    @Value("${geoji.oauth.google-client-id}") private String googleId;
    @Value("${geoji.oauth.google-client-secret}") private String googleSecret;
    @Value("${geoji.oauth.kakao-client-id}") private String kakaoId;
    @Value("${geoji.oauth.kakao-client-secret}") private String kakaoSecret;
    @Value("${geoji.oauth.apple-client-id}") private String appleId;
    @Value("${geoji.oauth.apple-team-id}") private String appleTeam;
    @Value("${geoji.oauth.apple-key-id}") private String appleKeyId;
    @Value("${geoji.oauth.apple-private-key-path}") private String appleKeyPath;

    public OAuthController(StringRedisTemplate redis, UserRepository users, OAuthAccountRepository accounts, TokenService tokens, ObjectMapper json) {
        this.redis = redis; this.users = users; this.accounts = accounts; this.tokens = tokens; this.json = json;
    }
    private record Pending(String provider, String nonce, String verifier, String linkUserId) {}
    private record Identity(String subject, String email) {}
    public record Exchange(String code) {}

    @GetMapping("/{provider}/start")
    public void start(@PathVariable String provider, @RequestParam(defaultValue="false") boolean link,
                      @AuthenticationPrincipal User currentUser, HttpServletResponse response) throws IOException {
        OAuthAccount.Provider p = provider(provider);
        ensureConfigured(p);
        String state = random(), nonce = random(), verifier = random();
        if (link && currentUser == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "계정 연결에는 로그인이 필요합니다.");
        try { redis.opsForValue().set("auth:oauth:state:" + state, json.writeValueAsString(new Pending(p.name(), nonce, verifier, link ? currentUser.getId().toString() : null)), Duration.ofMinutes(5)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        String url;
        if (p == OAuthAccount.Provider.GOOGLE) {
            url = UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("response_type", "code").queryParam("client_id", googleId).queryParam("redirect_uri", callback(p))
                .queryParam("scope", "openid email profile").queryParam("state", state).queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge(verifier)).queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
        } else if (p == OAuthAccount.Provider.KAKAO) {
            url = UriComponentsBuilder.fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("response_type", "code").queryParam("client_id", kakaoId).queryParam("redirect_uri", callback(p))
                .queryParam("state", state).queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256").build().encode().toUriString();
        } else {
            url = UriComponentsBuilder.fromUriString("https://appleid.apple.com/auth/authorize")
                .queryParam("response_type", "code id_token").queryParam("response_mode", "form_post")
                .queryParam("client_id", appleId).queryParam("redirect_uri", callback(p))
                .queryParam("scope", "name email").queryParam("state", state).queryParam("nonce", nonce)
                .build().encode().toUriString();
        }
        response.sendRedirect(url);
    }

    @GetMapping("/{provider}/callback")
    public void callbackGet(@PathVariable String provider, @RequestParam(required=false) String code,
                            @RequestParam(required=false) String state, @RequestParam(required=false) String error,
                            HttpServletResponse response) throws IOException {
        callback(provider, code, state, error, null, response);
    }
    @PostMapping(path="/apple/callback", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void callbackApple(@RequestParam(required=false) String code, @RequestParam(required=false) String state,
                              @RequestParam(required=false) String error, @RequestParam(name="id_token", required=false) String idToken,
                              HttpServletResponse response) throws IOException {
        callback("apple", code, state, error, idToken, response);
    }
    private void callback(String provider, String code, String state, String error, String appleIdToken, HttpServletResponse response) throws IOException {
        OAuthAccount.Provider p = provider(provider);
        if (error != null || code == null || state == null) throw bad("OAUTH_DENIED", "소셜 로그인이 취소되었거나 실패했습니다.");
        String stored = redis.opsForValue().getAndDelete("auth:oauth:state:" + state);
        if (stored == null) throw bad("INVALID_STATE", "소셜 로그인 요청이 만료되었습니다.");
        Pending pending = json.readValue(stored, Pending.class);
        if (!pending.provider().equals(p.name())) throw bad("INVALID_STATE", "소셜 로그인 요청이 올바르지 않습니다.");
        Identity identity = identify(p, code, pending, appleIdToken);
        OAuthAccount account = accounts.findByProviderAndProviderUserId(p, identity.subject()).orElse(null);
        User user;
        if (pending.linkUserId() != null) {
            user = users.findById(UUID.fromString(pending.linkUserId())).orElseThrow(() -> bad("ACCOUNT_NOT_FOUND", "계정을 찾을 수 없습니다."));
            if (user.getStatus() != User.Status.ACTIVE) throw bad("ACCOUNT_WITHDRAWN", "탈퇴한 계정입니다.");
            if (account != null && !account.getUser().getId().equals(user.getId())) throw bad("ACCOUNT_LINKED", "다른 계정에 연결된 소셜 계정입니다.");
            if (account == null) {
                account = new OAuthAccount(); account.setProvider(p); account.setProviderUserId(identity.subject()); account.setUser(user); accounts.save(account);
            }
        } else if (account != null) {
            user = account.getUser();
            if (user.getStatus() != User.Status.ACTIVE) throw bad("ACCOUNT_WITHDRAWN", "탈퇴한 계정입니다.");
        } else {
            user = new User();
            // An email match never links accounts without explicit authenticated linking.
            String email = identity.email();
            if (email != null && !users.existsByEmailIgnoreCaseAndStatus(email, User.Status.ACTIVE)) user.setEmail(email);
            user.setNickname("user-" + UUID.randomUUID().toString().substring(0, 8));
            user = users.save(user);
            account = new OAuthAccount(); account.setProvider(p); account.setProviderUserId(identity.subject()); account.setUser(user); accounts.save(account);
        }
        String exchange = random();
        redis.opsForValue().set("auth:oauth:exchange:" + exchange, user.getId().toString(), Duration.ofMinutes(1));
        response.sendRedirect(UriComponentsBuilder.fromUriString(successRedirect).queryParam("code", exchange).build().encode().toUriString());
    }
    @PostMapping("/exchange")
    public ApiResponse<TokenService.Tokens> exchange(@RequestBody Exchange input) {
        if (input == null || input.code() == null || input.code().isBlank()) throw bad("INVALID_CODE", "교환 코드가 필요합니다.");
        String id = redis.opsForValue().getAndDelete("auth:oauth:exchange:" + input.code());
        if (id == null) throw bad("INVALID_CODE", "교환 코드가 만료되었거나 이미 사용되었습니다.");
        User user = users.findById(UUID.fromString(id)).orElseThrow(() -> bad("INVALID_CODE", "계정을 찾을 수 없습니다."));
        if (user.getStatus() != User.Status.ACTIVE) throw bad("INVALID_CODE", "계정을 사용할 수 없습니다.");
        return ApiResponse.ok(tokens.issue(user));
    }

    private Identity identify(OAuthAccount.Provider p, String code, Pending pending, String appleIdToken) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code"); form.add("code", code); form.add("redirect_uri", callback(p));
            String tokenUrl;
            if (p == OAuthAccount.Provider.GOOGLE) {
                tokenUrl = "https://oauth2.googleapis.com/token";
                form.add("client_id", googleId); form.add("client_secret", googleSecret); form.add("code_verifier", pending.verifier());
            } else if (p == OAuthAccount.Provider.KAKAO) {
                tokenUrl = "https://kauth.kakao.com/oauth/token";
                form.add("client_id", kakaoId); form.add("client_secret", kakaoSecret); form.add("code_verifier", pending.verifier());
            } else {
                tokenUrl = "https://appleid.apple.com/auth/token";
                form.add("client_id", appleId); form.add("client_secret", appleSecret());
            }
            JsonNode result = http.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            if (result == null) throw bad("OAUTH_FAILED", "공급자 응답이 비어 있습니다.");
            if (p == OAuthAccount.Provider.KAKAO) {
                String access = result.path("access_token").asText();
                JsonNode profile = http.get().uri("https://kapi.kakao.com/v2/user/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access).retrieve().body(JsonNode.class);
                if (profile == null || !profile.hasNonNull("id")) throw bad("OAUTH_FAILED", "카카오 사용자 확인에 실패했습니다.");
                JsonNode kakaoAccount = profile.path("kakao_account");
                String email = kakaoAccount.path("is_email_verified").asBoolean(false) ? kakaoAccount.path("email").asText(null) : null;
                return new Identity(profile.get("id").asText(), email);
            }
            String idToken = result.path("id_token").asText();
            String jwks = p == OAuthAccount.Provider.GOOGLE ? "https://www.googleapis.com/oauth2/v3/certs" : "https://appleid.apple.com/auth/keys";
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
            Jwt jwt = decoder.decode(idToken);
            String issuer = p == OAuthAccount.Provider.GOOGLE ? "https://accounts.google.com" : "https://appleid.apple.com";
            String audience = p == OAuthAccount.Provider.GOOGLE ? googleId : appleId;
            if (!issuer.equals(jwt.getIssuer().toString()) || !jwt.getAudience().contains(audience)
                || !pending.nonce().equals(jwt.getClaimAsString("nonce"))) throw bad("OAUTH_FAILED", "ID 토큰 검증에 실패했습니다.");
            if (p == OAuthAccount.Provider.APPLE && appleIdToken != null) {
                Jwt frontToken = decoder.decode(appleIdToken);
                if (!jwt.getSubject().equals(frontToken.getSubject()) || !issuer.equals(frontToken.getIssuer().toString())
                    || !frontToken.getAudience().contains(audience) || !pending.nonce().equals(frontToken.getClaimAsString("nonce")))
                    throw bad("OAUTH_FAILED", "Apple ID 토큰 검증에 실패했습니다.");
            }
            Object verified = jwt.getClaim("email_verified");
            String email = Boolean.TRUE.equals(verified) || "true".equals(verified) ? jwt.getClaimAsString("email") : null;
            return new Identity(jwt.getSubject(), email);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw bad("OAUTH_FAILED", "소셜 인증을 완료하지 못했습니다."); }
    }
    private String appleSecret() {
        try {
            String pem = Files.readString(Path.of(appleKeyPath)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(appleTeam).subject(appleId).audience("https://appleid.apple.com")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofDays(7)))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(appleKeyId).build(), claims);
            jwt.sign(new com.nimbusds.jose.crypto.ECDSASigner((ECPrivateKey) key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException("Apple signing configuration is invalid", e); }
    }
    private void ensureConfigured(OAuthAccount.Provider p) {
        boolean ready = switch (p) {
            case GOOGLE -> !googleId.isBlank() && !googleSecret.isBlank();
            case KAKAO -> !kakaoId.isBlank() && !kakaoSecret.isBlank();
            case APPLE -> !appleId.isBlank() && !appleTeam.isBlank() && !appleKeyId.isBlank() && !appleKeyPath.isBlank();
        };
        if (!ready) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED", "공급자 설정이 완료되지 않았습니다.");
    }
    private OAuthAccount.Provider provider(String name) {
        try { return OAuthAccount.Provider.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw bad("INVALID_PROVIDER", "지원하지 않는 공급자입니다."); }
    }
    private String callback(OAuthAccount.Provider p) { return callbackBase + "/api/v1/auth/oauth/" + p.name().toLowerCase(Locale.ROOT) + "/callback"; }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
