package com.auditlog.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimestampNormalizer - Timestamp Parsing and UTC Normalization")
class TimestampNormalizerTest {

    @Test
    @DisplayName("Normalizes ISO-8601 timestamp to UTC")
    void testNormalizeToUtc() {
        String input = Instant.now().toString();
        String result = TimestampNormalizer.normalizeToUtcString(input);
        
        assertNotNull(result);
        assertTrue(result.endsWith("Z"), "Result should be UTC with Z suffix");
    }

    @Test
    @DisplayName("Handles timestamps with timezone offset")
    void testNormalizeWithTimezoneOffset() {
        // Create a timestamp 30 minutes in the past with +02:00 offset
        Instant time = Instant.now().minusSeconds(1800);
        String input = time.atZone(java.time.ZoneOffset.ofHours(2)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String result = TimestampNormalizer.normalizeToUtcString(input);
        
        assertNotNull(result);
        assertTrue(result.endsWith("Z"));
    }

    @Test
    @DisplayName("Null or empty timestamp uses current time")
    void testNullTimestampUsesNow() {
        String result1 = TimestampNormalizer.normalizeToUtcString(null);
        String result2 = TimestampNormalizer.normalizeToUtcString("");

        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(result1.endsWith("Z"));
        assertTrue(result2.endsWith("Z"));
    }

    @Test
    @DisplayName("Rejects timestamps beyond ±1 hour clock skew tolerance")
    void testRejectTimestampBeyondClockSkewTolerance() {
        long now = System.currentTimeMillis();
        long twoHoursAgo = now - (2 * 3600 * 1000);
        Instant twoHoursInstant = Instant.ofEpochMilli(twoHoursAgo);
        String oldTimestamp = twoHoursInstant.toString();

        assertThrows(
            IllegalArgumentException.class,
            () -> TimestampNormalizer.normalizeToUtcString(oldTimestamp),
            "Timestamp beyond clock skew tolerance should be rejected"
        );
    }

    @Test
    @DisplayName("Accepts timestamps within ±1 hour clock skew tolerance")
    void testAcceptTimestampWithinClockSkewTolerance() {
        long now = System.currentTimeMillis();
        long thirtyMinutesAgo = now - (30 * 60 * 1000);
        Instant thirtyMinutesInstant = Instant.ofEpochMilli(thirtyMinutesAgo);
        String recentTimestamp = thirtyMinutesInstant.toString();

        String result = TimestampNormalizer.normalizeToUtcString(recentTimestamp);
        assertNotNull(result);
        assertTrue(result.endsWith("Z"));
    }

    @Test
    @DisplayName("Invalid timestamp format throws exception")
    void testInvalidTimestampFormat() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TimestampNormalizer.normalizeToUtcString("not-a-timestamp"),
            "Invalid timestamp format should throw exception"
        );
    }

    @Test
    @DisplayName("Timestamp string ends with 'Z' for UTC")
    void testUtcTimestampSuffix() {
        String result = TimestampNormalizer.nowUtcString();
        assertTrue(result.endsWith("Z"), "UTC timestamp should end with 'Z'");
    }
}
