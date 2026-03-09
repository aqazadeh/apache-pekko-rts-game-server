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
    public static void main(String[] args) {
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

        // Simulate a client request
        EntityRef<KingdomCommand> player1Shard = sharding.entityRefFor(KingdomPlayerActor.ENTITY_KEY, "Player-123");

        System.out.println("Sending UpgradeBuildingCmd to Player-123...");
        player1Shard.tell(new UpgradeBuildingCmd("Castle", "MAIN_CASTLE"));

        // Keep running to observe logs
    }
}
