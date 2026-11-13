package com.geoji.app.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "users") @Getter @Setter
public class User {
    @Id private UUID id = UUID.randomUUID();
    @Column(length = 320) private String email;
    @Column(name = "password_hash") private String passwordHash;
    @Column(nullable = false, length = 50) private String nickname;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.ACTIVE;
    @Column(name = "auth_version", nullable = false) private int authVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "withdrawn_at") private Instant withdrawnAt;
    @PrePersist void create() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public enum Status { ACTIVE, WITHDRAWN }
}
