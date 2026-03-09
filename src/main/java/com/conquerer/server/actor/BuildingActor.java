package com.conquerer.server.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.building.BuildingState;
import com.conquerer.server.domain.player.*;
import com.conquerer.server.domain.building.*;

public class BuildingActor extends EventSourcedBehavior<BuildingCommand, BuildingEvent, BuildingState> {

    public static final EntityTypeKey<BuildingCommand> ENTITY_KEY = EntityTypeKey.create(BuildingCommand.class,
            "BuildingActor");

    private final String entityId;

    private BuildingActor(PersistenceId persistenceId, String entityId) {
        super(persistenceId);
        this.entityId = entityId;
    }

    public static Behavior<BuildingCommand> create(String entityId) {
        return Behaviors.setup(ctx -> new BuildingActor(PersistenceId.of(ENTITY_KEY.name(), entityId), entityId));
    }

    @Override
    public BuildingState emptyState() {
        return new BuildingState(entityId, 0, "IDLE", 0L);
    }

    @Override
    public CommandHandler<BuildingCommand, BuildingEvent, BuildingState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(StartConstructionCmd.class, this::onStartConstruction)
                .build();
    }

    private Effect<BuildingEvent, BuildingState> onStartConstruction(BuildingState state, StartConstructionCmd cmd) {
        long startTime = System.currentTimeMillis();
        ConstructionStartedEvent event = new ConstructionStartedEvent(cmd.buildingId(), cmd.targetLevel(), startTime);

        // First persist the Slave state ensuring the transaction commit in PostgreSQL
        return Effect().persist(event)
                .thenRun(newState -> {
                    // PERSIST SUCCESSFUL: Push the new update asynchronously back to Master!
                    // This replaces the need for context.ask and eliminates timeouts.
                    cmd.replyToMaster().tell(
                            new BuildingUpdateNotification(newState.buildingId(), newState.level(), newState.status()));
                });
    }

    @Override
    public EventHandler<BuildingState, BuildingEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ConstructionStartedEvent.class,
                        (state, event) -> new BuildingState(state.buildingId(), event.targetLevel(), "CONSTRUCTING",
                                event.startTime()))
                .build();
    }
}
