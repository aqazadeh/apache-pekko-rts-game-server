import os
import shutil
import glob
import re

base_path = "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server"

moves = {
    "domain/protocol/JsonSerializable.java": "domain/common/JsonSerializable.java",
    
    "domain/models/PlayerProfileAggregate.java": "domain/player/PlayerProfileAggregate.java",
    "domain/models/BuildingMirror.java": "domain/player/BuildingMirror.java",
    "domain/models/Resources.java": "domain/player/Resources.java",
    "domain/models/Progression.java": "domain/player/Progression.java",
    "domain/protocol/KingdomCommand.java": "domain/player/KingdomCommand.java",
    "domain/protocol/KingdomEvent.java": "domain/player/KingdomEvent.java",
    "domain/protocol/UpgradeBuildingCmd.java": "domain/player/UpgradeBuildingCmd.java",
    "domain/protocol/BuildingMirrorUpdatedEvent.java": "domain/player/BuildingMirrorUpdatedEvent.java",
    "domain/protocol/BuildingUpdateNotification.java": "domain/player/BuildingUpdateNotification.java",

    "domain/models/BuildingState.java": "domain/building/BuildingState.java",
    "domain/protocol/BuildingCommand.java": "domain/building/BuildingCommand.java",
    "domain/protocol/BuildingEvent.java": "domain/building/BuildingEvent.java",
    "domain/protocol/StartConstructionCmd.java": "domain/building/StartConstructionCmd.java",
    "domain/protocol/ConstructionStartedEvent.java": "domain/building/ConstructionStartedEvent.java",
    
    "domain/service/BuildingDomainService.java": "domain/building/BuildingDomainService.java",
}

package_map = {
    "com.conquerer.server.domain.protocol.JsonSerializable": "com.conquerer.server.domain.common.JsonSerializable",

    "com.conquerer.server.domain.models.PlayerProfileAggregate": "com.conquerer.server.domain.player.PlayerProfileAggregate",
    "com.conquerer.server.domain.models.BuildingMirror": "com.conquerer.server.domain.player.BuildingMirror",
    "com.conquerer.server.domain.models.Resources": "com.conquerer.server.domain.player.Resources",
    "com.conquerer.server.domain.models.Progression": "com.conquerer.server.domain.player.Progression",
    
    "com.conquerer.server.domain.protocol.KingdomCommand": "com.conquerer.server.domain.player.KingdomCommand",
    "com.conquerer.server.domain.protocol.KingdomEvent": "com.conquerer.server.domain.player.KingdomEvent",
    "com.conquerer.server.domain.protocol.UpgradeBuildingCmd": "com.conquerer.server.domain.player.UpgradeBuildingCmd",
    "com.conquerer.server.domain.protocol.BuildingMirrorUpdatedEvent": "com.conquerer.server.domain.player.BuildingMirrorUpdatedEvent",
    "com.conquerer.server.domain.protocol.BuildingUpdateNotification": "com.conquerer.server.domain.player.BuildingUpdateNotification",
    
    "com.conquerer.server.domain.models.BuildingState": "com.conquerer.server.domain.building.BuildingState",
    "com.conquerer.server.domain.protocol.BuildingCommand": "com.conquerer.server.domain.building.BuildingCommand",
    "com.conquerer.server.domain.protocol.BuildingEvent": "com.conquerer.server.domain.building.BuildingEvent",
    "com.conquerer.server.domain.protocol.StartConstructionCmd": "com.conquerer.server.domain.building.StartConstructionCmd",
    "com.conquerer.server.domain.protocol.ConstructionStartedEvent": "com.conquerer.server.domain.building.ConstructionStartedEvent",
    
    "com.conquerer.server.domain.service.BuildingDomainService": "com.conquerer.server.domain.building.BuildingDomainService"
}

for new_path in set(moves.values()):
    os.makedirs(os.path.dirname(os.path.join(base_path, new_path)), exist_ok=True)

for old_path, new_path in moves.items():
    src = os.path.join(base_path, old_path)
    dst = os.path.join(base_path, new_path)
    if os.path.exists(src):
        shutil.move(src, dst)

java_files = glob.glob(os.path.join(base_path, "**", "*.java"), recursive=True)

for file in java_files:
    with open(file, "r") as f:
        content = f.read()

    # Rewrite packages uniquely for the file's new location
    # Ex: if it's in com/conquerer/server/domain/player/
    rel_path = os.path.relpath(file, "/home/rovshan/videos/conquerer-2/src/main/java")
    new_pkg = rel_path.replace(os.sep, ".")[:-5] # remove .java
    new_pkg = ".".join(new_pkg.split(".")[:-1]) # remove class name
    content = re.sub(r"package\s+[\w\.]+;", f"package {new_pkg};", content)
    
    # Replace wildcard imports
    content = content.replace("import com.conquerer.server.domain.protocol.*;", 
                              "import com.conquerer.server.domain.common.*;\nimport com.conquerer.server.domain.player.*;\nimport com.conquerer.server.domain.building.*;")
    
    content = content.replace("import com.conquerer.server.domain.models.*;", 
                              "import com.conquerer.server.domain.player.*;\nimport com.conquerer.server.domain.building.*;")
    
    content = content.replace("import com.conquerer.server.domain.service.*;", 
                              "import com.conquerer.server.domain.building.*;")

    # Rewrite specific imports
    for old_fqn, new_fqn in package_map.items():
        content = content.replace(f"import {old_fqn};", f"import {new_fqn};")
        
    with open(file, "w") as f:
        f.write(content)

print("Refactor complete.")
