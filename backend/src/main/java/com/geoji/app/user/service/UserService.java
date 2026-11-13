package com.geoji.app.user.service;

import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public UserService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public User updateNickname(User user, String nickname) {
        user.setNickname(nickname.trim());
        return users.save(user);
    }

    @Transactional
    public void changePassword(User user, String current, String replacement) {
        if (user.getPasswordHash() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SOCIAL_ONLY", "비밀번호가 없는 소셜 계정입니다.");
        }
        if (!passwords.matches(current, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "현재 비밀번호가 올바르지 않습니다.");
        }
        user.setPasswordHash(passwords.encode(replacement));
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.save(user);
    }

    @Transactional
    public void withdraw(User user) {
        user.setStatus(User.Status.WITHDRAWN);
        user.setWithdrawnAt(Instant.now());
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.save(user);
    }
}
