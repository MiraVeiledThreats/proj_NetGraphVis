package netgraph.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;

/**
 * Single-worker, latest-request-wins projection builder with a small LRU cache.
 *
 * At most one projection build runs and at most one newer request is retained.
 * Rapid drag updates therefore cannot create an unbounded executor queue.
 *
 * A currently running build is not forcibly interrupted. ClusterProjectionBuilder
 * reads immutable graph data and captured coordinate arrays, so a stale result is
 * simply discarded after the build completes.
 */
public final class ClusterProjectionCoordinator {
    private static final int MAX_CACHE_ENTRIES = 10;

    private final ExecutorService worker;

    private final AtomicLong requestVersion =
            new AtomicLong();

    private final Object requestLock =
            new Object();

    private final Map<Key, ClusterProjection> cache =
            new LinkedHashMap<Key, ClusterProjection>(
                    16,
                    0.75f,
                    true) {

                private static final long serialVersionUID =
                        1L;

                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<Key, ClusterProjection> eldest) {

                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    /*
     * The following fields are guarded by requestLock.
     */
    private Request pendingRequest;
    private boolean drainScheduled;
    private boolean shutdown;

    public ClusterProjectionCoordinator() {
        worker =
                Executors.newSingleThreadExecutor(
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(
                                    final Runnable task) {

                                final Thread thread =
                                        new Thread(
                                                task,
                                                "network-cluster-projection");

                                thread.setDaemon(true);

                                return thread;
                            }
                        });
    }

    /**
     * Requests a visual projection.
     *
     * The supplied coordinate arrays must be immutable snapshots. GraphPanel
     * should copy layout coordinates while holding LayoutState.renderLock()
     * before calling this method.
     */
    public void request(
            final GraphStore store,
            final float[] x,
            final float[] y,
            final GraphDisplayFilter requestedFilter,
            final ClusterViewState.Snapshot state,
            final Listener listener) {

        if (store == null
                || store.isEmpty()
                || x == null
                || y == null
                || x.length < store.getNodeCount()
                || y.length < store.getNodeCount()
                || state == null
                || listener == null) {

            return;
        }

        final GraphDisplayFilter filter =
                requestedFilter == null
                        ? GraphDisplayFilter.all()
                        : requestedFilter;

        final Key key =
                new Key(
                        store,
                        filter.projectionSignature(),
                        state.signature());

        final long version =
                requestVersion.incrementAndGet();

        final ClusterProjection cached;

        synchronized (cache) {
            cached = cache.get(key);
        }

        if (cached != null) {
            publishIfCurrent(
                    store,
                    cached,
                    listener,
                    version);

            return;
        }

        synchronized (requestLock) {
            if (shutdown) {
                return;
            }

            /*
             * Latest request wins. The replaced request's coordinate arrays
             * become eligible for garbage collection immediately.
             */
            pendingRequest =
                    new Request(
                            store,
                            x,
                            y,
                            filter,
                            state,
                            listener,
                            key,
                            version);

            if (!drainScheduled) {
                drainScheduled = true;

                worker.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                drainRequests();
                            }
                        });
            }
        }
    }

    /**
     * Call after graph coordinates move or when a new GraphStore is installed.
     *
     * A running build can finish, but cannot be cached or delivered because its
     * version is stale. A queued-but-not-started request is removed immediately.
     */
    public void invalidate() {
        requestVersion.incrementAndGet();

        synchronized (cache) {
            cache.clear();
        }

        synchronized (requestLock) {
            pendingRequest = null;
        }
    }

    public void shutdown() {
        requestVersion.incrementAndGet();

        synchronized (requestLock) {
            shutdown = true;
            pendingRequest = null;
        }

        synchronized (cache) {
            cache.clear();
        }

        worker.shutdownNow();
    }

    private void drainRequests() {
        while (true) {
            final Request request;

            synchronized (requestLock) {
                request = pendingRequest;
                pendingRequest = null;

                if (request == null || shutdown) {
                    drainScheduled = false;
                    return;
                }
            }

            /*
             * Do not spend time building a projection invalidated before this
             * worker acquired the latest request.
             */
            if (requestVersion.get() != request.version) {
                continue;
            }

            final ClusterProjection projection =
                    ClusterProjectionBuilder.build(
                            request.store,
                            request.x,
                            request.y,
                            request.filter,
                            request.state);

            /*
             * Geometry, filter, graph, or cluster state changed during the
             * build. Do not cache or deliver obsolete output.
             */
            if (requestVersion.get() != request.version) {
                continue;
            }

            synchronized (cache) {
                if (requestVersion.get() == request.version) {
                    cache.put(
                            request.key,
                            projection);
                }
            }

            publishIfCurrent(
                    request.store,
                    projection,
                    request.listener,
                    request.version);
        }
    }

    private void publishIfCurrent(
            final GraphStore store,
            final ClusterProjection projection,
            final Listener listener,
            final long version) {

        SwingUtilities.invokeLater(
                new Runnable() {
                    @Override
                    public void run() {
                        if (requestVersion.get() == version) {
                            listener.accept(
                                    store,
                                    projection);
                        }
                    }
                });
    }

    public interface Listener {
        void accept(
                GraphStore store,
                ClusterProjection projection);
    }

    private static final class Request {
        private final GraphStore store;
        private final float[] x;
        private final float[] y;
        private final GraphDisplayFilter filter;
        private final ClusterViewState.Snapshot state;
        private final Listener listener;
        private final Key key;
        private final long version;

        private Request(
                final GraphStore store,
                final float[] x,
                final float[] y,
                final GraphDisplayFilter filter,
                final ClusterViewState.Snapshot state,
                final Listener listener,
                final Key key,
                final long version) {

            this.store = store;
            this.x = x;
            this.y = y;
            this.filter = filter;
            this.state = state;
            this.listener = listener;
            this.key = key;
            this.version = version;
        }
    }

    private static final class Key {
        private final GraphStore store;
        private final String filter;
        private final String state;

        private Key(
                final GraphStore store,
                final String filter,
                final String state) {

            this.store = store;
            this.filter = filter;
            this.state = state;
        }

        @Override
        public int hashCode() {
            int result =
                    System.identityHashCode(store);

            result =
                    31 * result + filter.hashCode();

            return 31 * result + state.hashCode();
        }

        @Override
        public boolean equals(
                final Object value) {

            if (!(value instanceof Key)) {
                return false;
            }

            final Key other =
                    (Key) value;

            return store == other.store
                    && filter.equals(other.filter)
                    && state.equals(other.state);
        }
    }
}