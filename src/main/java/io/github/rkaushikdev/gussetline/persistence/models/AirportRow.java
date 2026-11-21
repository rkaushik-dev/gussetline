package io.github.rkaushikdev.gussetline.persistence.models;

import io.github.rkaushikdev.gussetline.persistence.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One airport, keyed by its code, and the city and country it belongs to. */
@Entity
@Table(
        name = "airports",
        indexes = {
            @Index(name = "ix_airports_city", columnList = "city"),
            @Index(name = "ix_airports_country", columnList = "country")
        })
public class AirportRow extends Timestamped {

    @Id
    @Column(name = "code", length = 3, nullable = false)
    private String code;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "city", length = 3, nullable = false)
    private String city;

    @Column(name = "country", length = 2, nullable = false)
    private String country;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "utc_offset_minutes", nullable = false)
    private int utcOffsetMinutes;

    @Column(name = "source", length = 16, nullable = false)
    private String source = "shipped";

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public int getUtcOffsetMinutes() {
        return utcOffsetMinutes;
    }

    public void setUtcOffsetMinutes(int utcOffsetMinutes) {
        this.utcOffsetMinutes = utcOffsetMinutes;
    }

    /** Where the row came from: {@code shipped} unless somebody says otherwise. */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    /** {@code <Airport FRA in FRA>}. */
    @Override
    public String toString() {
        return "<Airport " + code + " in " + city + ">";
    }
}
