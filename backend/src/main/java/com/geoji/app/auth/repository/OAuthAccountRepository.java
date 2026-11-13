package com.geoji.app.auth.repository;

import com.geoji.app.auth.entity.OAuthAccount;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthAccount.Provider provider, String providerUserId);
}
