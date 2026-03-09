package com.conquerer.server.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.RecoveryCompleted;
import org.apache.pekko.persistence.typed.javadsl.*;

import com.conquerer.server.domain.building.*;
import com.conquerer.server.domain.player.*;

import java.time.Duration;

/**
 * BuildingActor — player-scoped manager actor.
 *
 * EntityId = "Buildings-{playerId}" (e.g. "Buildings-player-1")
 *
 * A single instance of this actor manages ALL buildings for one player.
 * Its state is {@link BuildingsState}, which holds a Map<buildingId,
 * BuildingDetail>.
 *
 * Lifecycle of an upgrade:
 * Master BuildingActor Timer
 * │─── StartConstructionCmd ──────────►│
 * │ │── persist ConstructionStartedEvent
 * │ │── scheduleOnce(duration) ──────────►│
 * │◄── BuildingUpdateNotification(CONSTRUCTING) ───│
 * │ │ │ (time passes)
 * │ │◄──────────│ CompleteConstructionCmd
 * │ │── persist ConstructionCompletedEvent
 * │◄── BuildingUpdateNotification(COMPLETED) ──────│
 *
 * Crash Recovery:
 * On RecoveryCompleted, any building still CONSTRUCTING gets its timer
 * rescheduled for the remaining duration (or fired immediately if overdue).
 */
public class BuildingActor
        extends EventSourcedBehavior<BuildingCommand, BuildingEvent, BuildingsState> {

    // -----------------------------------------------------------------------
    // Sharding key — entity ID = "Buildings-{playerId}"
    // -----------------------------------------------------------------------
    public static final EntityTypeKey<BuildingCommand> ENTITY_KEY = EntityTypeKey.create(BuildingCommand.class,
            "BuildingActor");

    private final String entityId; // "Buildings-player-1"
    private final ActorContext<BuildingCommand> context;
    private final ClusterSharding sharding;

    private BuildingActor(ActorContext<BuildingCommand> context,
            PersistenceId persistenceId,
            String entityId) {
        super(persistenceId);
        this.context = context;
        this.entityId = entityId;
        this.sharding = ClusterSharding.get(context.getSystem());
    }

    /**
     * Factory — called by ClusterSharding.
     * entityId should be "Buildings-{playerId}".
     */
    public static Behavior<BuildingCommand> create(String entityId) {
        return Behaviors.setup(ctx -> new BuildingActor(ctx,
                PersistenceId.of(ENTITY_KEY.name(), entityId),
                entityId));
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    @Override
    public BuildingsState emptyState() {
        return BuildingsState.EMPTY;
    }

    // -----------------------------------------------------------------------
    // Retention / Snapshotting
    // -----------------------------------------------------------------------

    @Override
    public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(5, 2);
    }

    // -----------------------------------------------------------------------
    // Recovery — reschedule timers for every CONSTRUCTING building
    // -----------------------------------------------------------------------

    @Override
    public SignalHandler<BuildingsState> signalHandler() {
        return newSignalHandlerBuilder()
                .onSignal(RecoveryCompleted.instance(), this::onRecoveryCompleted)
                .build();
    }

    /**
     * After journal replay: scan the map for any building still CONSTRUCTING
     * and reschedule its completion timer.
     *
     * NOTE: ActorRef is not serializable across JVM restarts, so
     * {@link CompleteConstructionCmd#replyToMaster()} will be {@code null}
     * for recovered timers. The actor persists the event durably; the Master
     * can re-query state if it misses the push notification.
     */
    private void onRecoveryCompleted(BuildingsState state) {
        state.buildings().forEach((buildingId, detail) -> {
            if (!"CONSTRUCTING".equals(detail.status()))
                return;

            BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(detail.type());
            Duration fullDuration = config.getDurationForLevel(detail.level());
            long elapsed = System.currentTimeMillis() - detail.startTime();
            long remainingMs = fullDuration.toMillis() - elapsed;

            if (remainingMs <= 0) {
                // Already overdue — trigger completion immediately
                context.getLog().warn(
                        "[BuildingActor:{}] {} recovered CONSTRUCTING but timer overdue. Completing now.",
                        entityId, buildingId);
                context.getSelf().tell(
                        new CompleteConstructionCmd(buildingId, detail.level() + 1, null));
            } else {
                context.getLog().info(
                        "[BuildingActor:{}] {} recovered CONSTRUCTING — rescheduling timer in {}ms",
                        entityId, buildingId, remainingMs);
                context.scheduleOnce(
                        Duration.ofMillis(remainingMs),
                        context.getSelf(),
                        new CompleteConstructionCmd(buildingId, detail.level() + 1, null));
            }
        });
    }

    // -----------------------------------------------------------------------
    // Command Handler
    // -----------------------------------------------------------------------

    @Override
    public CommandHandler<BuildingCommand, BuildingEvent, BuildingsState> commandHandler() {
        return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(StartConstructionCmd.class, this::onStartConstruction)
                .onCommand(CompleteConstructionCmd.class, this::onCompleteConstruction)
                .build();
    }

    /**
     * Master requests a building upgrade.
     *
     * Guard: if the building is already CONSTRUCTING, reject silently.
     */
    private Effect<BuildingEvent, BuildingsState> onStartConstruction(
            BuildingsState state, StartConstructionCmd cmd) {

        // ── Duplicate check ────────────────────────────────────────────────
        if (state.isConstructing(cmd.buildingId())) {
            context.getLog().warn(
                    "[BuildingActor:{}] {} is already CONSTRUCTING — ignoring duplicate request.",
                    entityId, cmd.buildingId());
            return Effect().none();
        }

        long startTime = System.currentTimeMillis();
        ConstructionStartedEvent event = new ConstructionStartedEvent(
                cmd.buildingId(), cmd.buildingType(), cmd.targetLevel(), startTime);

        return Effect().persist(event)
                .thenRun(newState -> {
                    // Determine upgrade duration
                    BuildingDetail detail = newState.buildings().get(cmd.buildingId());
                    int currentLevel = detail != null ? detail.level() : 0;
                    BuildingUpgradeConfig config = BuildingUpgradeConfig.fromType(cmd.buildingType());
                    Duration upgradeDuration = config.getDurationForLevel(currentLevel);

                    context.getLog().info(
                            "[BuildingActor:{}] {} (type={}) level {} → {} | duration: {}",
                            entityId, cmd.buildingId(), cmd.buildingType(),
                            currentLevel, cmd.targetLevel(), upgradeDuration);

                    // Schedule internal completion command
                    context.scheduleOnce(
                            upgradeDuration,
                            context.getSelf(),
                            new CompleteConstructionCmd(
                                    cmd.buildingId(), cmd.targetLevel(), cmd.replyToMaster()));

                    // Notify Master: CONSTRUCTING
                    if (cmd.replyToMaster() != null) {
                        cmd.replyToMaster().tell(new BuildingUpdateNotification(
                                cmd.buildingId(), currentLevel, "CONSTRUCTING"));
                    }
                });
    }

    /**
     * Timer elapsed — building upgrade is done.
     * Persist COMPLETED event and notify Master.
     */
    private Effect<BuildingEvent, BuildingsState> onCompleteConstruction(
            BuildingsState state, CompleteConstructionCmd cmd) {

        long completedAt = System.currentTimeMillis();
        context.getLog().info(
                "[BuildingActor:{}] {} upgrade complete → level {}",
                entityId, cmd.buildingId(), cmd.targetLevel());

        ConstructionCompletedEvent event = new ConstructionCompletedEvent(
                cmd.buildingId(), cmd.targetLevel(), completedAt);

        return Effect().persist(event)
                .thenRun(newState -> {
                    BuildingDetail detail = newState.buildings().get(cmd.buildingId());
                    int finalLevel = detail != null ? detail.level() : cmd.targetLevel();

                    if (cmd.replyToMaster() != null) {
                        cmd.replyToMaster().tell(new BuildingUpdateNotification(
                                cmd.buildingId(), finalLevel, "COMPLETED"));
                    } else {
                        // Recovered after crash — ActorRef lost, state is durable.
                        context.getLog().info(
                                "[BuildingActor:{}] {} completed (post-recovery) — no live master ref.",
                                entityId, cmd.buildingId());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Event Handler — applies events onto BuildingsState
    // -----------------------------------------------------------------------

    @Override
    public EventHandler<BuildingsState, BuildingEvent> eventHandler() {
        return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(ConstructionStartedEvent.class,
                        (state, event) -> state.withConstructionStarted(event))
                .onEvent(ConstructionCompletedEvent.class,
                        (state, event) -> state.withConstructionCompleted(event))
                .build();
    }
}
