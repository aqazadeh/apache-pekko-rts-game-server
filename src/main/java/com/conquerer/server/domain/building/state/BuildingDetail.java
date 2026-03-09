package com.conquerer.server.domain.building.state;

import com.conquerer.server.domain.common.JsonSerializable;

/**
 * Immutable snapshot of a single building's state inside BuildingsState.
 *
 * level     — current completed level (increases only on COMPLETION)
 * status    — IDLE | CONSTRUCTING | COMPLETED
 * startTime — epoch-ms when the current construction started (0 if IDLE)
 */
public record BuildingDetail(
        String id,
        String type,
        int level,
        String status,
        long startTime
) implements JsonSerializable {}

