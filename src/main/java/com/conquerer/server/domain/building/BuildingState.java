package com.conquerer.server.domain.building;

import com.conquerer.server.domain.common.JsonSerializable;

public record BuildingState(
    String buildingId,
    String buildingType,
    int level,
    String status,
    long constructionStartTime
) implements JsonSerializable {}

