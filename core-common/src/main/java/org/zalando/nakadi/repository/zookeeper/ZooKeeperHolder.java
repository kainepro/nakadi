package org.zalando.nakadi.repository.zookeeper;

import org.apache.curator.ensemble.EnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.storage.AddressPort;
import org.zalando.nakadi.domain.storage.ZookeeperConnection;
import org.zalando.nakadi.exceptions.runtime.ZookeeperException;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ZooKeeperHolder {

    private static final int CURATOR_LOCKS_INSTANCE_LIVE_PERIOD = 1000 * 60 * 5; // 5 minutes
    private static final int CURATOR_RETRY_TIME = 1000;
    private static final int CURATOR_RETRY_MAX = 3;

    private final Integer connectionTimeoutMs;
    private final long maxCommitTimeoutMs;
    private final Integer sessionTimeoutMs;
    private final ZookeeperConnection conn;

    private CuratorFramework zooKeeper;
    private CuratorFramework subscriptionCurator;
    private CuratorFramework locksCurator;

    private long locksCuratorCreatedAt;
    private final Object curatorLocksLock;

    public ZooKeeperHolder(final ZookeeperConnection conn,
                           final Integer sessionTimeoutMs,
                           final Integer connectionTimeoutMs,
                           final NakadiSettings nakadiSettings) throws Exception {
        this.conn = conn;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.maxCommitTimeoutMs = TimeUnit.SECONDS.toMillis(nakadiSettings.getMaxCommitTimeout());
        this.curatorLocksLock = new Object();

        zooKeeper = createCuratorFramework(sessionTimeoutMs, connectionTimeoutMs);
        subscriptionCurator = createCuratorFramework((int) maxCommitTimeoutMs, connectionTimeoutMs);
    }

    public CuratorFramework get() {
        return zooKeeper;
    }

    /**
     * During ConnectionLoss event under certain conditions (unknown yet)
     * Curator does not clean acquired leases, which makes it impossible
     * for clients to acquire a lease.
     * New curator lock instance is intended to avoid such issue by
     * closing Zookeeper session, which will closure of associated
     * ephemeral znodes like leases.
     */
    public CuratorFramework getLocksCurator() throws ZookeeperException {
        synchronized (curatorLocksLock) {
            if (locksCurator == null) {
                try {
                    locksCurator = createCuratorFramework(sessionTimeoutMs, connectionTimeoutMs);
                } catch (Exception e) {
                    throw new ZookeeperException(
                            "Failed to create curator framework", e);
                }

                locksCuratorCreatedAt = System.currentTimeMillis();
                return locksCurator;
            }

            if (System.currentTimeMillis() >
                    locksCuratorCreatedAt +
                            CURATOR_LOCKS_INSTANCE_LIVE_PERIOD) {
                locksCurator.close();
                locksCurator = null;
                try {
                    locksCurator = createCuratorFramework(
                            sessionTimeoutMs,
                            connectionTimeoutMs);
                } catch (Exception e) {
                    throw new ZookeeperException(
                            "Failed to create curator framework", e);
                }

                locksCuratorCreatedAt = System.currentTimeMillis();
            }
        }

        return locksCurator;
    }

    public CloseableCuratorFramework getSubscriptionCurator(final long sessionTimeoutMs) throws ZookeeperException {
        // most of the clients use default max timeout, subscriptionCurator client saves zookeeper resource
        if (sessionTimeoutMs == maxCommitTimeoutMs) {
            return new StaticCuratorFramework(subscriptionCurator);
        }

        try {
            // max commit timeout is not higher than 60 seconds, it is safe to cast to integer
            return new DisposableCuratorFramework(createCuratorFramework((int) sessionTimeoutMs, connectionTimeoutMs));
        } catch (final Exception e) {
            throw new ZookeeperException("Failed to create curator framework", e);
        }
    }

    public abstract static class CloseableCuratorFramework implements Closeable {

        private final CuratorFramework curatorFramework;

        public CloseableCuratorFramework(final CuratorFramework curatorFramework) {
            this.curatorFramework = curatorFramework;
        }

        public CuratorFramework getCuratorFramework() {
            return curatorFramework;
        }
    }

    public static class StaticCuratorFramework extends CloseableCuratorFramework {

        public StaticCuratorFramework(final CuratorFramework curatorFramework) {
            super(curatorFramework);
        }

        @Override
        public void close() {
            // do not ever close this particular instance of curator
        }
    }

    public static class DisposableCuratorFramework extends CloseableCuratorFramework {

        public DisposableCuratorFramework(final CuratorFramework curatorFramework) {
            super(curatorFramework);
        }

        @Override
        public void close() {
            getCuratorFramework().close();
        }
    }

    private CuratorFramework createCuratorFramework(final int sessionTimeoutMs,
                                                    final int connectionTimeoutMs) throws Exception {
        final CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
                .ensembleProvider(createEnsembleProvider())
                .retryPolicy(new ExponentialBackoffRetry(CURATOR_RETRY_TIME, CURATOR_RETRY_MAX))
                .sessionTimeoutMs(sessionTimeoutMs)
                .connectionTimeoutMs(connectionTimeoutMs)
                .build();
        curatorFramework.start();
        return curatorFramework;
    }

    private EnsembleProvider createEnsembleProvider() throws Exception {
        switch (conn.getType()) {
            case ZOOKEEPER:
                final String addressesJoined = conn.getAddresses().stream()
                        .map(AddressPort::asAddressPort)
                        .collect(Collectors.joining(","));
                return new ChrootedFixedEnsembleProvider(addressesJoined, conn.getPathPrepared());
            default:
                throw new RuntimeException("Connection type " + conn.getType() + " is not supported");
        }
    }
}
