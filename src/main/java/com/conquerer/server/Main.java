package com.conquerer.server;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;

import com.conquerer.server.actor.BuildingActor;
import com.conquerer.server.actor.KingdomPlayerActor;
import com.conquerer.server.domain.player.KingdomCommand;
import com.conquerer.server.domain.player.UpgradeBuildingCmd;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Bootstrap the cluster on a simple guardian actor
        ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "ConquererSystem");

        ClusterSharding sharding = ClusterSharding.get(system);

        // Initialize Shards
        sharding.init(
                Entity.of(KingdomPlayerActor.ENTITY_KEY,
                        entityContext -> KingdomPlayerActor.create(entityContext.getEntityId(), sharding)));

        sharding.init(
                Entity.of(BuildingActor.ENTITY_KEY,
                        entityContext -> BuildingActor.create(entityContext.getEntityId())));

        // Give the cluster a moment to form
        Thread.sleep(2000);

        // Simulate Player-123 upgrading multiple buildings concurrently
        // Each building type has its own scheduler duration (see BuildingUpgradeConfig)
        EntityRef<KingdomCommand> player1 = sharding.entityRefFor(KingdomPlayerActor.ENTITY_KEY, "Player-123");

        System.out.println("=================================================");
        System.out.println("  Sending upgrade commands for Player-123...");
        System.out.println("  CASTLE   → base 120s  (slowest)");
        System.out.println("  BARRACKS → base 30s");
        System.out.println("  FARM     → base 20s   (fastest)");
        System.out.println("  MINE     → base 25s");
        System.out.println("=================================================");

        player1.tell(new UpgradeBuildingCmd("castle-1", "CASTLE"));
        player1.tell(new UpgradeBuildingCmd("barracks-1", "BARRACKS"));
        player1.tell(new UpgradeBuildingCmd("farm-1", "FARM"));
        player1.tell(new UpgradeBuildingCmd("mine-1", "MINE"));

        // Keep running long enough to observe all schedulers completing
        // In production this is irrelevant; actors live as long as the system runs.
        System.out.println("System running — waiting for schedulers to fire...");
    }
}

