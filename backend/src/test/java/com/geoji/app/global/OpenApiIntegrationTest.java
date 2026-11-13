package com.geoji.app.global;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void docsArePublicAndDescribeBearerAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist());
        mvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized());
    }
}
