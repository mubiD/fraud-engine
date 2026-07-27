package com.fraudengine.repository;

import com.fraudengine.model.MerchantLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MerchantLocationRepository extends JpaRepository<MerchantLocation, String> {
}
