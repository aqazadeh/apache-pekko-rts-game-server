package com.conquerer.server.domain.building;

import com.conquerer.server.domain.player.state.BuildingMirror;
import com.conquerer.server.domain.player.state.PlayerProfileAggregate;

public class BuildingDomainService {

    public static boolean canUpgrade(PlayerProfileAggregate state, String buildingId) {
        BuildingMirror mirror = state.buildings().get(buildingId);
        int currentLevel = mirror != null ? mirror.level() : 0;

        long cost = calculateGoldCost(currentLevel);
        return state.resources().gold() >= cost && currentLevel < 30;
    }

    private static long calculateGoldCost(int level) {
        return (long) (100 * Math.pow(1.5, level));
    }
}
