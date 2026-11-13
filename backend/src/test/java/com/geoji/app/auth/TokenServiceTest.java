package com.geoji.app.auth;

import com.geoji.app.auth.service.TokenService;

import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import com.geoji.app.user.controller.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {
    @Test void refreshTokenCanBeRotatedOnlyOnce() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var stored = new HashMap<String,String>();
        doAnswer(inv -> { stored.put(inv.getArgument(0), inv.getArgument(1)); return null; })
            .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.getAndDelete(anyString())).thenAnswer(inv -> stored.remove(inv.getArgument(0)));
        var users = mock(UserRepository.class);
        var user = new User(); user.setNickname("tester");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var service = new TokenService(key, "geoji-test", redis, users);
        var initial = service.issue(user);
        var rotated = service.rotate(initial.refreshToken());
        assertNotEquals(initial.refreshToken(), rotated.refreshToken());
        assertThrows(ApiException.class, () -> service.rotate(initial.refreshToken()));
    }

    @Test void wrongKeyAndMalformedAccessTokenAreRejected() {
        var redis = mock(StringRedisTemplate.class);
        var users = mock(UserRepository.class);
        assertThrows(IllegalStateException.class, () -> new TokenService("bad", "geoji-test", redis, users));
        var service = new TokenService(Base64.getEncoder().encodeToString(new byte[32]), "geoji-test", redis, users);
        assertThrows(ApiException.class, () -> service.authenticated("not-a-jwt"));
    }
}
