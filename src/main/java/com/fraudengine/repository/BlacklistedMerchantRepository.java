package com.fraudengine.repository;

import com.fraudengine.model.BlacklistedMerchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BlacklistedMerchantRepository extends JpaRepository<BlacklistedMerchant, String> {
}
