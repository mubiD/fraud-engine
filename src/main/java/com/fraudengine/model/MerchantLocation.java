package com.fraudengine.model;

import jakarta.persistence.*;

@Entity
@Table(name = "merchant_locations")
public class MerchantLocation {

    @Id
    @Column(name = "merchant_id")
    private String merchantId;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private String city;
    private String country;

    public MerchantLocation() {}

    public String getMerchantId() { return merchantId; }
    public Double getLatitude()   { return latitude; }
    public Double getLongitude()  { return longitude; }
    public String getCity()       { return city; }
    public String getCountry()    { return country; }

    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setLatitude(Double latitude)     { this.latitude = latitude; }
    public void setLongitude(Double longitude)   { this.longitude = longitude; }
    public void setCity(String city)             { this.city = city; }
    public void setCountry(String country)       { this.country = country; }
}
