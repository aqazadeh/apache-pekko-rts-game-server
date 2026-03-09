package com.conquerer.server.domain.building;

import java.time.Duration;

/**
 * Defines upgrade duration per building type and level.
 * Each building type has a base duration that scales with level.
 *
 * Formula: baseDuration * (1 + level * scalingFactor)
 */
public enum BuildingUpgradeConfig {

    BARRACKS(Duration.ofSeconds(30), 0.5),
    FARM(Duration.ofSeconds(20), 0.3),
    MINE(Duration.ofSeconds(25), 0.4),
    WALL(Duration.ofSeconds(60), 0.8),
    TOWER(Duration.ofSeconds(45), 0.6),
    CASTLE(Duration.ofSeconds(120), 1.0),
    UNKNOWN(Duration.ofSeconds(15), 0.2);

    private final Duration baseDuration;
    private final double scalingFactor;

    BuildingUpgradeConfig(Duration baseDuration, double scalingFactor) {
        this.baseDuration = baseDuration;
        this.scalingFactor = scalingFactor;
    }

    /**
     * Returns the upgrade duration for the given current level.
     * Higher levels take longer to upgrade.
     */
    public Duration getDurationForLevel(int currentLevel) {
        long baseMillis = baseDuration.toMillis();
        long scaled = (long) (baseMillis * (1.0 + currentLevel * scalingFactor));
        return Duration.ofMillis(scaled);
    }

    /**
     * Resolves the config from a buildingType string (case-insensitive).
     * Falls back to UNKNOWN if not matched.
     */
    public static BuildingUpgradeConfig fromType(String buildingType) {
        if (buildingType == null) return UNKNOWN;
        try {
            return valueOf(buildingType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
