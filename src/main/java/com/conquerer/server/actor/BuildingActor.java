package com.conquerer.server.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.RecoveryCompleted;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.building.*;
import com.conquerer.server.domain.player.*;

import java.time.Duration;

public class BuildingActor extends EventSourcedBehavior<BuildingCommand, BuildingEvent, BuildingState> {

    public static final EntityTypeKey<BuildingCommand> ENTITY_KEY = EntityTypeKey.create(BuildingCommand.class,
            "BuildingActor");

    private final String entityId;
    private final ActorContext<BuildingCommand> context;

    private BuildingActor(ActorContext<BuildingCommand> context, PersistenceId persistenceId, String entityId) {
        super(persistenceId);
        this.context = context;
        this.entityId = entityId;
    }

    public static Behavior<BuildingCommand> create(String entityId) {
        return Behaviors.setup(ctx -> new BuildingActor(ctx, PersistenceId.of(ENTITY_KEY.name(), entityId), entityId));
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    @Override
    public BuildingState emptyState() {
        return new BuildingState(entityId, "UNKNOWN", 0, "IDLE", 0L);
    }

    // -------------------------------------------------------------------------
    // Recovery — reschedule timer if still CONSTRUCTING after journal replay
    // -------------------------------------------------------------------------
    @Override
    public SignalHandler<BuildingState> signalHandler() {
        return newSignalHandlerBuilder()
                .onSignal(RecoveryCompleted.instance(), this::onRecoveryCompleted)
                .build();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(5, 2);
    }

    /**
     * Called once journal replay is done. If we crashed mid-upgrade, the state will
     * be CONSTRUCTING. We recalculate the remaining time and reschedule the timer.
     *
     * NOTE: Because we lost the replyToMaster ActorRef across restarts, we send
     *       a self-contained InternalCompleteCmd that carries NO reply ref —
     *       the actor will simply persist the COMPLETED event. The Master will
     *       receive the notification when it re-queries state, or we can wire
     *       a "poll" mechanism. For now, we persist the result durably.
     */
    private void onRecoveryCompleted(BuildingState state) {
        if (!"CONSTRUCTING".equals(state.status())) return;

        BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(state.buildingType());
        Duration fullDuration = config.getDurationForLevel(state.level());
        long elapsed = System.currentTimeMillis() - state.constructionStartTime();
        long remainingMs = fullDuration.toMillis() - elapsed;

        if (remainingMs <= 0) {
            // Already overdue — complete immediately
            context.getLog().warn(
                    "[BuildingActor] {} recovered CONSTRUCTING but timer already overdue. Completing now.",
                    state.buildingId());
            context.getSelf().tell(new CompleteConstructionCmd(state.buildingId(), state.level() + 1, null));
        } else {
            context.getLog().info(
                    "[BuildingActor] {} recovered CONSTRUCTING — rescheduling timer in {}ms",
                    state.buildingId(), remainingMs);
            context.scheduleOnce(
                    Duration.ofMillis(remainingMs),
                    context.getSelf(),
                    new CompleteConstructionCmd(state.buildingId(), state.level() + 1, null));
        }
    }

    // -------------------------------------------------------------------------
    // Command Handler
    // -------------------------------------------------------------------------
    @Override
    public CommandHandler<BuildingCommand, BuildingEvent, BuildingState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(StartConstructionCmd.class, this::onStartConstruction)
                .onCommand(CompleteConstructionCmd.class, this::onCompleteConstruction)
                .build();
    }

    /**
     * Phase 1: Master asks us to start upgrading.
     * Persist ConstructionStartedEvent (includes buildingType for recovery).
     * Schedule a timer for the upgrade duration.
     * Immediately notify Master with CONSTRUCTING status.
     */
    private Effect<BuildingEvent, BuildingState> onStartConstruction(BuildingState state, StartConstructionCmd cmd) {
        if ("CONSTRUCTING".equals(state.status())) {
            context.getLog().warn("[BuildingActor] {} already CONSTRUCTING — ignoring duplicate request.", cmd.buildingId());
            return Effect().none();
        }

        long startTime = System.currentTimeMillis();
        // buildingType is now part of the event so it survives crash recovery
        ConstructionStartedEvent event = new ConstructionStartedEvent(
                cmd.buildingId(), cmd.buildingType(), cmd.targetLevel(), startTime);

        return Effect().persist(event)
                .thenRun(newState -> {
                    BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(cmd.buildingType());
                    Duration upgradeDuration = config.getDurationForLevel(state.level());

                    context.getLog().info(
                            "[BuildingActor] {} (type={}) level {} → {} | duration: {}",
                            cmd.buildingId(), cmd.buildingType(), state.level(), cmd.targetLevel(), upgradeDuration);

                    context.scheduleOnce(
                            upgradeDuration,
                            context.getSelf(),
                            new CompleteConstructionCmd(cmd.buildingId(), cmd.targetLevel(), cmd.replyToMaster()));

                    // Notify Master: construction STARTED
                    if (cmd.replyToMaster() != null) {
                        cmd.replyToMaster().tell(
                                new BuildingUpdateNotification(newState.buildingId(), newState.level(), newState.status()));
                    }
                });
    }

    /**
     * Phase 2: Timer fires — upgrade duration elapsed.
     * Persist ConstructionCompletedEvent. Notify Master with COMPLETED status.
     * replyToMaster may be null after crash recovery (ActorRef not serializable across JVM restarts).
     */
    private Effect<BuildingEvent, BuildingState> onCompleteConstruction(BuildingState state, CompleteConstructionCmd cmd) {
        long completedAt = System.currentTimeMillis();
        context.getLog().info("[BuildingActor] {} upgrade complete → level {}", cmd.buildingId(), cmd.targetLevel());

        ConstructionCompletedEvent event = new ConstructionCompletedEvent(
                cmd.buildingId(), cmd.targetLevel(), completedAt);

        return Effect().persist(event)
                .thenRun(newState -> {
                    if (cmd.replyToMaster() != null) {
                        cmd.replyToMaster().tell(
                                new BuildingUpdateNotification(newState.buildingId(), newState.level(), newState.status()));
                    } else {
                        // Recovered after crash — no live ref available.
                        // State is durable; Master can re-query via GetBuildingStateQuery (future work).
                        context.getLog().info(
                                "[BuildingActor] {} completed (recovered) — no live master ref to notify.",
                                cmd.buildingId());
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Event Handler
    // -------------------------------------------------------------------------
    @Override
    public EventHandler<BuildingState, BuildingEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ConstructionStartedEvent.class,
                        (state, event) -> new BuildingState(
                                state.buildingId(),
                                event.buildingType(),    // persisted → survives recovery
                                state.level(),           // level stays same while CONSTRUCTING
                                "CONSTRUCTING",
                                event.startTime()))
                .onEvent(ConstructionCompletedEvent.class,
                        (state, event) -> new BuildingState(
                                state.buildingId(),
                                state.buildingType(),
                                event.completedLevel(),  // level advances only on COMPLETION
                                "COMPLETED",
                                state.constructionStartTime()))
                .build();
    }
}
