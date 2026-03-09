package com.conquerer.server.domain.building.state;

import com.conquerer.server.domain.building.event.ConstructionCompletedEvent;
import com.conquerer.server.domain.building.event.ConstructionStartedEvent;
import com.conquerer.server.domain.common.JsonSerializable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregate state for one player's BuildingActor manager.
 *
 * Holds a Map of buildingId → BuildingDetail so a single actor
 * can track every building owned by that player.
 *
 * The class is intentionally immutable-friendly: mutation methods
 * return a NEW BuildingsState with the updated map.
 */
public final class BuildingsState implements JsonSerializable {

    /** Empty initial state — no buildings yet. */
    public static final BuildingsState EMPTY = new BuildingsState(Map.of());

    private final Map<String, BuildingDetail> buildings;

    public BuildingsState(Map<String, BuildingDetail> buildings) {
        // defensive copy: every instance owns its own snapshot
        this.buildings = Collections.unmodifiableMap(new HashMap<>(buildings));
    }

    // -----------------------------------------------------------------------
    // Read accessors
    // -----------------------------------------------------------------------

    public Map<String, BuildingDetail> buildings() {
        return buildings;
    }

    public boolean isConstructing(String buildingId) {
        BuildingDetail detail = buildings.get(buildingId);
        return detail != null && "CONSTRUCTING".equals(detail.status());
    }

    public boolean hasBuilding(String buildingId) {
        return buildings.containsKey(buildingId);
    }

    // -----------------------------------------------------------------------
    // Event-application helpers — return new state, old state is untouched
    // -----------------------------------------------------------------------

    /**
     * Applied when {@link ConstructionStartedEvent} is persisted.
     * Level stays unchanged while CONSTRUCTING; startTime is recorded.
     */
    public BuildingsState withConstructionStarted(ConstructionStartedEvent event) {
        int currentLevel = hasBuilding(event.buildingId())
                ? buildings.get(event.buildingId()).level()
                : 0;

        BuildingDetail updated = new BuildingDetail(
                event.buildingId(),
                event.buildingType(),
                currentLevel,          // level advances ONLY upon completion
                "CONSTRUCTING",
                event.startTime()
        );

        Map<String, BuildingDetail> next = new HashMap<>(buildings);
        next.put(event.buildingId(), updated);
        return new BuildingsState(next);
    }

    /**
     * Applied when {@link ConstructionCompletedEvent} is persisted.
     * Level advances to completedLevel; status becomes COMPLETED.
     */
    public BuildingsState withConstructionCompleted(ConstructionCompletedEvent event) {
        BuildingDetail current = buildings.get(event.buildingId());
        String type = current != null ? current.type() : "UNKNOWN";
        long startTime = current != null ? current.startTime() : 0L;

        BuildingDetail updated = new BuildingDetail(
                event.buildingId(),
                type,
                event.completedLevel(),   // ← level now advances
                "COMPLETED",
                startTime
        );

        Map<String, BuildingDetail> next = new HashMap<>(buildings);
        next.put(event.buildingId(), updated);
        return new BuildingsState(next);
    }
}
