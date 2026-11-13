package com.geoji.app.auth;

import com.geoji.app.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AuthApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @MockitoBean StringRedisTemplate redis;

    @Test void signupHashesPasswordAndProtectedEndpointRequiresJwt() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@example.com\",\"password\":\"strong-password\",\"nickname\":\"member\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.email").value("member@example.com"));
        var user = users.findByEmailIgnoreCaseAndStatus("member@example.com", com.geoji.app.user.entity.User.Status.ACTIVE).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals("strong-password", user.getPasswordHash());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"member@example.com\",\"password\":\"strong-password\",\"nickname\":\"member\"}"))
            .andExpect(status().isConflict());
    }
}
