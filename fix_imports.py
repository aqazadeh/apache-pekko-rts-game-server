import glob

files = [
    "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server/domain/building/BuildingCommand.java",
    "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server/domain/building/BuildingEvent.java",
    "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server/domain/player/KingdomCommand.java",
    "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server/domain/player/KingdomEvent.java"
]

for file in files:
    with open(file, "r") as f:
        content = f.read()
    if "import com.conquerer.server.domain.common.JsonSerializable;" not in content:
        content = content.replace("package ", "package ") # no op
        # insert after package
        parts = content.split(";\n", 1)
        content = parts[0] + ";\n\nimport com.conquerer.server.domain.common.JsonSerializable;\n" + parts[1]
    with open(file, "w") as f:
        f.write(content)

# StartConstructionCmd needs KingdomCommand
f2 = "/home/rovshan/videos/conquerer-2/src/main/java/com/conquerer/server/domain/building/StartConstructionCmd.java"
with open(f2, "r") as f:
    content = f.read()
if "KingdomCommand" in content and "import com.conquerer.server.domain.player.KingdomCommand;" not in content:
    parts = content.split(";\n", 1)
    content = parts[0] + ";\n\nimport com.conquerer.server.domain.player.KingdomCommand;\n" + parts[1]
with open(f2, "w") as f:
    f.write(content)

print("done")
