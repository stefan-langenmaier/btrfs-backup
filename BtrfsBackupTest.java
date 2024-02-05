///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-api:5.10.0
//DEPS org.junit.jupiter:junit-jupiter-engine:5.10.0
//DEPS org.junit.platform:junit-platform-launcher:1.10.0

//SOURCES BtrfsBackup.java

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;


import org.junit.platform.launcher.listeners.LoggingListener;

import static java.lang.System.out;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class BtrfsBackupTest {

    Config config;

    @Test
    public void testGetLatestCommonSnapshot() {
        assertTrue(true);
    }

    @Test
    public void testSnapshotPruning() {
        Set<Snapshot> allSnapshots = Set.of(
            SnapshotUtils.of("ID 1 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-201000"),
            SnapshotUtils.of("ID 2 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-191000"),
            SnapshotUtils.of("ID 3 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-181000"),
            SnapshotUtils.of("ID 4 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-181100"),
            SnapshotUtils.of("ID 5 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20230104-181000")
        );

        Set<Snapshot> expectedSnapshots = Set.of(
            SnapshotUtils.of("ID 1 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-201000"),
            SnapshotUtils.of("ID 2 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-191000"),
            SnapshotUtils.of("ID 4 gen 0 top level 259 path <FS_TREE>/@snapshots/test-20240104-181100")
        );

        Set<Snapshot> prunedSnapshots = SnapshotUtils.prune(allSnapshots, config, "test");

        assertEquals(expectedSnapshots, prunedSnapshots);
    }

    @BeforeEach
    public void init() {
        config = new Config();

        config.now = LocalDateTime.now(
            Clock.fixed(Instant.parse("2024-01-04T20:15:30.00Z"), ZoneId.of("UTC"))
        );
        Map<String, Config.Retention> retentions = Map.of(
            "hour", new Config.Retention(Duration.parse("PT1H"), 10),
            "daily", new Config.Retention(Duration.parse("PT24H"), 24)
        );

        Map<String, Config.Volume> volumes = Map.of(
            "test", new Config.Volume("/test", retentions)
        );

        config.volumes=volumes;
    }

    // Run all Unit tests with JBang with ./BtrfsSnapshotBackup.java
    public static void main(final String... args) {
        Logger logger = Logger.getLogger(BtrfsBackupTest.class.getName());
        final LauncherDiscoveryRequest request =
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectClass(BtrfsBackupTest.class))
                        .build();
        final Launcher launcher = LauncherFactory.create();
        final LoggingListener logListener = LoggingListener.forBiConsumer((t,m) -> {
            logger.info(m.get());
            if(t!=null) {
                t.printStackTrace();
            }
        });
        final SummaryGeneratingListener execListener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(execListener, logListener);
        launcher.execute(request);
        execListener.getSummary().printTo(new java.io.PrintWriter(out));
    }
}
