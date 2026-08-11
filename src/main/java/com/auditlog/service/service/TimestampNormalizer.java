package com.auditlog.service.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimestampNormalizer {
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final long CLOCK_SKEW_TOLERANCE_MILLIS = 3600_000L;

    public static String normalizeToUtcString(String timestamp) {
        return normalizeToUtcString(timestamp, Clock.systemUTC());
    }

    public static String normalizeToUtcString(String timestamp, Clock clock) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return nowUtcString(clock);
        }

        try {
            String normalized = timestamp;
            if (timestamp.endsWith("Z")) {
                normalized = timestamp.substring(0, timestamp.length() - 1) + "+00:00";
            }
            
            OffsetDateTime odt = OffsetDateTime.parse(normalized, ISO_OFFSET);
            Instant instant = odt.toInstant();
            
            long now = clock.instant().toEpochMilli();
            long provided = instant.toEpochMilli();
            long diff = Math.abs(now - provided);
            
            if (diff > CLOCK_SKEW_TOLERANCE_MILLIS) {
                throw new IllegalArgumentException(
                    "Timestamp is outside acceptable clock skew tolerance (±1 hour): " + timestamp
                );
            }
            
            return instant.atZone(ZoneId.of("UTC"))
                .format(ISO_OFFSET)
                .replace("+00:00", "Z");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid timestamp format: " + timestamp, e);
        }
    }

    public static String nowUtcString() {
        return nowUtcString(Clock.systemUTC());
    }

    public static String nowUtcString(Clock clock) {
        return clock.instant().atZone(ZoneId.of("UTC"))
            .format(ISO_OFFSET)
            .replace("+00:00", "Z");
    }

    public static Instant parseUtcString(String utcString) {
        String normalized = utcString;
        if (utcString.endsWith("Z")) {
            normalized = utcString.substring(0, utcString.length() - 1) + "+00:00";
        }
        return OffsetDateTime.parse(normalized, ISO_OFFSET).toInstant();
    }
}
