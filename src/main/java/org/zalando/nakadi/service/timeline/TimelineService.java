package org.zalando.nakadi.service.timeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.Storage;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.domain.VersionedCursor;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.db.TimelineDbRepository;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class TimelineService {
    private final TimelineDbRepository timelineRepo;
    private final EventTypeRepository eventTypeRepo;
    private final TimelineSync timelineSync;
    private final StorageWorkerFactory storageWorkerFactory;

    @Autowired
    public TimelineService(
            final TimelineDbRepository timelineRepo,
            final EventTypeRepository eventTypeRepo,
            final TimelineSync timelineSync,
            final StorageWorkerFactory storageWorkerFactory) {
        this.timelineRepo = timelineRepo;
        this.eventTypeRepo = eventTypeRepo;
        this.timelineSync = timelineSync;
        this.storageWorkerFactory = storageWorkerFactory;
    }

    public List<Storage> listStorages() {
        return timelineRepo.getStorages();
    }

    public Optional<Storage> getStorage(final String id) {
        return timelineRepo.getStorage(id);
    }

    public Storage getDefaultStorage() {
        return timelineRepo.getDefaultStorage();
    }

    public void createOrUpdateStorage(final Storage storage) throws NakadiException {
        final Optional<Storage> existing = timelineRepo.getStorage(storage.getId());
        if (existing.isPresent()) {
            if (!existing.get().getType().equals(storage.getType())) {
                throw new IllegalArgumentException("Can not change storage type for " + storage.getId());
            }
            final List<Timeline> timelines =
                    timelineRepo.listTimelines(null, storage.getId(), null, null);
            if (!timelines.isEmpty()) {
                throw new IllegalStateException("Timelines are present for storage " + storage.getId());
            }
            timelineRepo.update(storage);
        } else {
            timelineRepo.create(storage);
        }
    }

    public void deleteStorage(final String storageId) {
        timelineRepo.getStorage(storageId).ifPresent(storage -> {
            final List<Timeline> existingTimelines =
                    timelineRepo.listTimelines(null, storage.getId(), null, null);
            if (!existingTimelines.isEmpty()) {
                throw new IllegalStateException("Storage " + storage.getId() + " have linked timelines. Can not delete");
            }
            timelineRepo.delete(storage);
        });
    }

    public List<Timeline> listTimelines(final String eventType) {
        return timelineRepo.listTimelines(eventType, null, null, null);
    }

    @Transactional
    public Timeline createAndStartTimeline(final String eventTypeName, final String storageId) throws NakadiException, InterruptedException {
        final Storage storage = timelineRepo.getStorage(storageId)
                .orElseThrow(() -> new IllegalArgumentException("Storage with id " + storageId + " is not found"));
        final EventType eventType = eventTypeRepo.findByName(eventTypeName);
        final Timeline activeTimeline = timelineRepo.loadActiveTimeline(eventTypeName);

        final Timeline timeline = new Timeline();
        timeline.setCreatedAt(new Date());
        timeline.setEventType(eventTypeName);
        timeline.setStorage(storage);

        final StorageWorker oldStorageWorker = storageWorkerFactory.getWorker(
                null == activeTimeline ? timelineRepo.getDefaultStorage() : activeTimeline.getStorage());
        final StorageWorker newStorageWorker = storageWorkerFactory.getWorker(storage);

        if (null == activeTimeline) {
            // Here everything is simple.
            // Nothing will be changed - topic will remain the same, but it will start to work with different offsets
            // format.
            // We don't have to stop publishers, but we will, because it will reduce complexity of update process.
            if (!storage.equals(timelineRepo.getDefaultStorage())) {
                throw new IllegalArgumentException("The very first change should go to default storage");
            }
            timeline.setStorageConfiguration(newStorageWorker.createEventTypeConfiguration(
                    eventType,
                    oldStorageWorker.getTopicRepository().listPartitionNames(
                            oldStorageWorker.createFakeTimeline(eventType).getStorageConfiguration()).size(),
                    true));
            timeline.setOrder(0);
        } else {

            timeline.setStorageConfiguration(newStorageWorker.createEventTypeConfiguration(
                    eventType,
                    oldStorageWorker.getTopicRepository().listPartitionNames(activeTimeline.getStorageConfiguration()).size(),
                    false));
            timeline.setOrder(activeTimeline.getOrder() + 1);
        }

        // Now perform actual switch (try to do it within 60 seconds).
        timelineSync.startTimelineUpdate(eventType.getName(), TimeUnit.SECONDS.toMillis(60));
        try {
            // Now all nodes have stopped publishing to event type, they are waiting for unblock.
            timeline.setSwitchedAt(new Date());
            if (null != activeTimeline) {
                // Wee need to write latest cursors configuration.
                activeTimeline.setLastPosition(oldStorageWorker.getLatestPosition(activeTimeline));
                timelineRepo.update(activeTimeline);
            }
            return timelineRepo.create(timeline);
        } finally {
            // Inform everyone that it's time to reread information.
            timelineSync.finishTimelineUpdate(eventType.getName());
        }
    }


    public void deleteTimeline(final String eventType, final Integer timelineId) throws InterruptedException {
        // The only possible way to delete timeline - delete active timeline that has order 0 and is linked to default
        // storage.
        final Timeline timeline = timelineRepo.getTimeline(timelineId);
        if (null == timeline) {
            throw new IllegalArgumentException("Timeline with id " + timelineId + " is not found");
        }
        if (!timeline.getEventType().equals(eventType)) {
            throw new IllegalArgumentException("Timeline event type: " + timeline.getEventType() + ", provided: " + eventType);
        }
        if (timeline.getOrder() != 0) {
            throw new IllegalStateException("Can remove only the very first timeline");
        }
        if (!Objects.equals(timeline.getId(), timelineRepo.loadActiveTimeline(eventType).getId())) {
            throw new IllegalStateException("Timeline is not the latest one!");
        }
        timelineSync.startTimelineUpdate(timeline.getEventType(), TimeUnit.SECONDS.toMillis(60));
        try {
            timelineRepo.delete(timeline);
        } finally {
            timelineSync.finishTimelineUpdate(timeline.getEventType());
        }
    }

    public Timeline getTimeline(final EventType et) {
        final Timeline existing = timelineRepo.loadActiveTimeline(et.getName());
        if (null != existing) {
            return existing;
        }
        return storageWorkerFactory.getWorker(timelineRepo.getDefaultStorage()).createFakeTimeline(et);
    }

    public Timeline createTimelineForNewEventType(final EventType eventType) throws NakadiException {
        // TODO: One should be able to change default storage to active storage!
        final StorageWorker storageWorker = storageWorkerFactory.getWorker(timelineRepo.getDefaultStorage());
        final Timeline.EventTypeConfiguration etConfig = storageWorker.createEventTypeConfiguration(
                eventType, null, false);
        final Timeline timeline = new Timeline();
        timeline.setStorage(storageWorker.getStorage());
        timeline.setEventType(eventType.getName());
        timeline.setStorageConfiguration(etConfig);
        timeline.setCreatedAt(new Date());
        timeline.setSwitchedAt(timeline.getCreatedAt());
        timeline.setOrder(0);
        timelineRepo.create(timeline);
        return timeline;
    }

    @Nullable
    public Timeline getTimeline(final EventType eventType, final VersionedCursor vCursor) throws InternalNakadiException, NoSuchEventTypeException {
        if (vCursor instanceof VersionedCursor.VersionedCursorV0) {
            return storageWorkerFactory.getWorker(getDefaultStorage()).createFakeTimeline(eventType);
        } else if (vCursor instanceof VersionedCursor.VersionedCursorV1) {
            final VersionedCursor.VersionedCursorV1 v1 = (VersionedCursor.VersionedCursorV1) vCursor;
            return timelineRepo.getTimeline(v1.getTimelineId());
        } else {
            throw new IllegalArgumentException("Cursor class " + vCursor.getClass() + " is not supported");
        }
    }
}
