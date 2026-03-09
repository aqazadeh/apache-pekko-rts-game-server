package com.conquerer.server.domain.player;

import com.conquerer.server.domain.common.JsonSerializable;

public record BuildingMirror(String buildingId, String type, int level, String status) implements JsonSerializable {}
