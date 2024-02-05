///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//COMPILE_OPTIONS --enable-preview -source 21
//RUNTIME_OPTIONS --enable-preview
//NATIVE_OPTIONS --enable-preview
// this disables warnings regarding the annotation processing,
//COMPILE_OPTIONS -Xlint:-options
//DEPS info.picocli:picocli:4.7.5
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1
//DEPS org.apache.commons:commons-lang3:3.14.0
// deps for graalvm
//DEPS info.picocli:picocli-codegen:4.7.5
//DEPS io.goodforgod:graalvm-hint-processor:1.1.1
//DEPS io.goodforgod:graalvm-hint-annotations:1.1.1
//FILES resources/config/backup.yaml.sample

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static java.lang.StringTemplate.STR;

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.goodforgod.graalvm.hint.annotation.ReflectionHint;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParseResult;

@Command(
    name = "BtrfsBackup",
    mixinStandardHelpOptions = true,
    version = "BtrfsBackup 0.1",
    description = "Do remote backup of btrfs volumes via ssh"
)
class BtrfsBackup implements Callable<Integer> {
    static Logger logger = Logger.getLogger(BtrfsBackup.class.getName());

    Config config;

    @CommandLine.Option(
        names = {"-v"},
        description = "Can be used multiple times to set the log level",
        required = false
    )
    private boolean[] verbosity = new boolean[0];

    @CommandLine.Option(
        names = {"-x"},
        description = "Print config",
        required = false
    )
    private boolean printConfig = false;

    @Parameters(
        index = "0",
        description = "The volumes to backup",
        arity = "0..*"
    )
    private List<String> volumes = Collections.emptyList();

    private int executionStrategy(ParseResult parseResult) {
        init(); // custom initialization to be done before executing any command or subcommand
        return new CommandLine.RunLast().execute(parseResult); // default execution strategy
    }

    private void init() {
        logger.setLevel(
            switch (verbosity.length) {
                case 0 -> Level.INFO;
                case 1 -> Level.FINE;
                case 2 -> Level.FINER;
                default -> Level.FINEST;
        });

        if (printConfig) {
            printConfig();
            System.exit(0);
            return;
        }

        config = ConfigLoader.loadDefaults();

        config.activeVolumes = config.volumes;
        if (!volumes.isEmpty()) {
            config.activeVolumes = config.volumes.entrySet().stream()
                .filter(v -> volumes.contains(v.getKey()))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
        }

        BtrfsRunner.setConfig(config);
        SnapshotUtils.setConfig(config);
    }

    private void printConfig() {
        System.err.println("Following is an example config file");
        System.err.println("You can store it in the current working dir or in ~/.config/ as backup.yaml");

        InputStream inputStream = BtrfsBackup.class.getResourceAsStream("backup.yaml.sample");

        // the stream holding the file content
        if (inputStream == null) {
            throw new IllegalArgumentException("resource for sample config not found!");
        }

        try (InputStreamReader streamReader =
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(streamReader)) {

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String... args) {
        var app = new BtrfsBackup();
        int exitCode = new CommandLine(app)
            .setExecutionStrategy(app::executionStrategy)
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        snapshot();
        backup();
        prune();

        return 0;
    }

    @Command(name = "prune")
    public Integer prune() throws Exception {
        for (var volumeId : config.activeVolumes.keySet()) {
            prune(volumeId, config);
        }

        return 0;
    }

    @Command(name = "snapshot")
    public Integer snapshot() throws Exception {
        for (var volumeId : config.activeVolumes.keySet()) {
            snapshot(volumeId, config);
        }

        return 0;
    }

    @Command(name = "backup")
    public Integer backup() throws Exception {
        for (var volumeId : config.activeVolumes.keySet()) {
            backup(volumeId, config);
        }

        return 0;
    }

    private void prune(String volumeId, Config config) throws Exception {
        Process processLocalSnapshots = BtrfsRunner.executeLocal("sub", "list",  config.snapshots.path().toString());
        ParseSnapshotsTask parser = new ParseSnapshotsTask(volumeId, processLocalSnapshots.getInputStream());
        Future<List<Snapshot>> futureLocalSnapshots = config.pool.submit(parser);
        var localSnapshots = futureLocalSnapshots.get();
        var keepSnapshots = SnapshotUtils.prune(futureLocalSnapshots.get(), config, volumeId);

        for (Snapshot s : localSnapshots) {
            if (!keepSnapshots.contains(s)) {
                logger.log(Level.FINE, STR."Deleting snapshot: \{ s.volumeName() } - \{ s.creationId() }");
                Process process = BtrfsRunner.executeLocal("sub", "delete", s.path().toString());
                process.waitFor();
                logger.log(Level.FINE, "Deletion completed");
            }
        }
    }

    private void snapshot(String volumeId, Config config) throws Exception {
        logger.log(Level.FINE, "Taking snapshot");
        Process process = BtrfsRunner.executeLocal("sub", "snap", "-r", config.volumes.get(volumeId).path(), SnapshotUtils.getNewSnapshotPath(volumeId));
        process.waitFor();
        logger.log(Level.FINE, "Snapshot completed");
    }

    private void backup(String volumeId, Config config) throws Exception {
        Process processLocalSnapshots = BtrfsRunner.executeLocal("sub", "list",  config.snapshots.path().toString());
        ParseSnapshotsTask parser = new ParseSnapshotsTask(volumeId, processLocalSnapshots.getInputStream());
        Future<List<Snapshot>> futureLocalSnapshots = config.pool.submit(parser);

        Process processRemoteSnapshots = BtrfsRunner.executeRemote("sub", "list",  config.backup.path());
        ParseSnapshotsTask parseRemoteSnapshots = new ParseSnapshotsTask(volumeId, processRemoteSnapshots.getInputStream());
        Future<List<Snapshot>> futureRemoteSnapshots = config.pool.submit(parseRemoteSnapshots);

        Optional<Snapshot> latestCommonSnapshot = SnapshotUtils.getLatestCommonSnapshot(futureLocalSnapshots.get(), futureRemoteSnapshots.get());

        ProcessBuilder sendProcess;
        if (latestCommonSnapshot.isPresent()) {
            // do incremental sync
            logger.log(Level.FINE, "Incremental backup transfer");
            sendProcess = BtrfsRunner.prepareLocal("send", "-v", "-p", latestCommonSnapshot.get().path().toString(), SnapshotUtils.getLatestSnapshot(futureLocalSnapshots.get()).path().toString());
        } else {
            // there is no common parent we need to do a full sync
            logger.log(Level.FINE, "Full backup transfer");
            sendProcess = BtrfsRunner.prepareLocal("send", "-v", SnapshotUtils.getLatestSnapshot(futureLocalSnapshots.get()).path().toString());
        }

        final var builders = Arrays.asList(
            sendProcess,
            new ProcessBuilder("pv") // estimating the size is slow and incorrect
                .redirectError(ProcessBuilder.Redirect.INHERIT),
            BtrfsRunner.prepareRemote("receive",  config.backup.path()).redirectError(ProcessBuilder.Redirect.INHERIT)
        );

        config.status.pause();
        var processes = ProcessBuilder.startPipeline(builders);
        processes.get(2).waitFor();
        config.status.resume();
    }

    public class BtrfsRunner {
        private static Config config;
        private static final String[] localPrefix = new String[] {"sudo", "btrfs"};

        private BtrfsRunner() {}

        public static void setConfig(Config config) {
            BtrfsRunner.config = config;
        }

        public static Process executeLocal(String... command) {
            return execute(prepareLocal(command));
        }

        public static Process executeRemote(String... command) {
            return execute(prepareRemote(command));
        }

        public static ProcessBuilder prepareLocal(String... command) {
            return prepare(ArrayUtils.addAll(localPrefix, command));
        }

        public static ProcessBuilder prepareRemote(String... command) {
            final String[] remotePrefix = new String[] {"ssh", config.backup.user() + "@" + config.backup.host()};

            var prefix = ArrayUtils.addAll(remotePrefix, localPrefix);
            return prepare(ArrayUtils.addAll(prefix, command));
        }

        private static ProcessBuilder prepare(String... command) {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(command);
            return processBuilder;
        }

        private static Process execute(ProcessBuilder pb) {
            Process process = null;

            try {
                process = pb.start();
                ProcessReadTask task = new ProcessReadTask(process.getErrorStream());
                config.pool.submit(task);
            } catch (IOException e) {
                e.printStackTrace();
                logger.log(Level.SEVERE, "unable to start sub process");
                System.exit(-1);
                return null;
            }

            return process;
        }
    }

    public class ConfigLoader {

        private ConfigLoader() {}

        public static Config loadDefaults() {
            Config c;
            Path configPath;
            configPath = Paths.get("./backup.yaml");
            if (!configPath.toFile().exists()) {
                configPath = Paths.get(System.getenv("XDG_CONFIG_HOME") + "backup.yaml");
            }
            if (!configPath.toFile().exists()) {
                configPath = Paths.get(System.getenv("HOME") + ".config/backup.yaml");
            }
            if (!configPath.toFile().exists()) {
                logger.log(Level.SEVERE, "no configuration file found");
                System.exit(-1);
                return null;
            }
            try (InputStream inputStream = Files.newInputStream(configPath)) {
                ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                objectMapper.registerModule(new JavaTimeModule());
                c = objectMapper.readValue(inputStream, Config.class);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "could not open configuration file");
                e.printStackTrace();
                System.exit(-1);
                return null;
            }

            c.now = LocalDateTime.now();
            c.pool = Executors.newWorkStealingPool();

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("sudo", "btrfs", "version");
            try {
                var process = processBuilder.start();
                process.waitFor();
                if (process.exitValue() != 0) {
                    logger.log(Level.SEVERE, "No permission to use btrfs with sudo");
                    System.exit(-1);
                }
            } catch (IOException e) {
                e.printStackTrace();
                logger.log(Level.SEVERE, "Error while checking for permissions");
                System.exit(-1);
            } catch (InterruptedException e) {
                e.printStackTrace();
                logger.log(Level.SEVERE, "Interrupted while checking for permissions");
                Thread.currentThread().interrupt();
                System.exit(-1);
            }

            c.status = new SpinnerTask();
            Executors.newSingleThreadExecutor().submit(c.status);

            return c;
        }
    }
}

class SnapshotUtils {
    private static Config config;

    private SnapshotUtils() {}

    public static void setConfig(Config config) {
        SnapshotUtils.config = config;
    }

    public static Optional<Snapshot> getLatestCommonSnapshot(List<Snapshot> localSnapshots,
            List<Snapshot> remoteSnapshots) {
        localSnapshots.sort(Comparator.comparing(Snapshot::getCreationId).reversed());
        remoteSnapshots.sort(Comparator.comparing(Snapshot::getCreationId).reversed());

        return localSnapshots.stream()
            .filter(remoteSnapshots::contains)
            .findFirst();
    }

    public static void getRelativeSize(String snapshotPath) {
        // could be implemented via a send (metadata) -> receive dump analysis
    }

    public static Snapshot of(String snapshot) {
        String regex = "ID (\\d+) gen (\\d+) top level (\\d+) path (.+)";

        Matcher matcher = Pattern.compile(regex).matcher(snapshot);
        if (!matcher.matches()) {
            return null;
        }
        long id = Long.parseLong(matcher.group(1));
        long generation = Long.parseLong(matcher.group(2));
        long parent = Long.parseLong(matcher.group(3));
        Path path = config.snapshots.path().resolve(Paths.get(matcher.group(4)));
        String lastDirectory = path.getFileName().toString();
        regex = "(.+)-(\\d{8}-\\d{6})";
        matcher = Pattern.compile(regex).matcher(lastDirectory);
        if (!matcher.matches()) {
            return new Snapshot(id, generation, parent, path);
        }
        // TODO make this parsing configurable
        String volumeName = matcher.group(1);
        String creationId = matcher.group(2);
        return new Snapshot(id, generation, parent, path, volumeName, creationId);

    }

    // prunes the snapshots and leave a set of snapshots according to the retention rules in the config
    public static Set<Snapshot> prune(Collection<Snapshot> snapshots, Config config, String volumeName) {
        Set<Snapshot> result = new HashSet<>();

        for (Config.Retention retention : config.volumes.get(volumeName).retention().values()) {
            result.addAll(snapshots
                .stream()
                .filter(s -> s.getCreationDateTime().isAfter(config.now.minus(retention.duration())))
                .collect(
                    Collectors.groupingBy(
                        snapshot -> getLatestPossibleDate(snapshot, retention.duration().dividedBy(retention.count())),
                        Collectors.maxBy(Comparator.comparing(Snapshot::getCreationDateTime))
                    )
                )
                .values()
                .stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet())
            );
        }

        return result;
    }

    private static LocalDateTime getLatestPossibleDate(Snapshot snapshot, Duration windowDuration) {
        // Round down the given dateTime to the nearest windowDuration
        long windowSeconds = windowDuration.getSeconds();
        long epochSeconds = snapshot.getCreationDateTime().toEpochSecond(ZoneOffset.UTC);
        long roundedEpochSeconds = (epochSeconds / windowSeconds) * windowSeconds;
        return LocalDateTime.ofEpochSecond(roundedEpochSeconds, 0, ZoneOffset.UTC);
    }

    public static String getNewSnapshotPath(String volumeId) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

        return String.format("%s/%s-%s", config.snapshots.path(), volumeId, config.now.format(formatter));
    }

    public static Snapshot getLatestSnapshot(List<Snapshot> snapshots) {
        snapshots.sort(Comparator.comparing(Snapshot::getCreationId).reversed());

        return snapshots.get(0);
    }

}

@ReflectionHint
class Config {
    public Path rootVolume;
    public Backup backup;
    public Snapshots snapshots;
    public Map<String, Volume> volumes;
    public Map<String, Volume> activeVolumes;

    public LocalDateTime now;
    public ExecutorService pool;
    public SpinnerTask status;

    @ReflectionHint record Backup (String user, String host, String path, String protocol) {}
    @ReflectionHint record Volume (String path, Map<String, Retention> retention) {}
    @ReflectionHint record Retention (Duration duration, Integer count) {}
    @ReflectionHint record Snapshots (Path path) {}
}

record Snapshot (
    long id,
    long generation,
    long parent,
    Path path,
    String volumeName,
    String creationId
) {

    public Snapshot(long id, long generation, long parent, Path path) {
        this(id, generation, parent, path, null, null);
    }

    public String getCreationId() {
        return creationId;
    }

    public LocalDateTime getCreationDateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        return LocalDateTime.parse(creationId, formatter);
    }

    public boolean isNullSnapshot() {
        return this.path == null;
    }

    public boolean isSnapshot() {
        return this.creationId != null && !isNullSnapshot();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other instanceof Snapshot s) {
            if (s.volumeName.equals(this.volumeName) && s.creationId.equals(this.creationId)) {
                return true;
            }
        }

        return false;
    }
}

class ProcessReadTask implements Callable<List<String>> {

    private InputStream inputStream;

    public ProcessReadTask(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public List<String> call() {
        var is = new InputStreamReader(inputStream);
        return new BufferedReader(is)
            .lines()
            .collect(Collectors.toList());
    }
}

class ParseSnapshotsTask implements Callable<List<Snapshot>> {

    private InputStream inputStream;
    private String volumeId;

    public ParseSnapshotsTask(String volumeId, InputStream inputStream) {
        this.inputStream = inputStream;
        this.volumeId = volumeId;
    }

    @Override
    public List<Snapshot> call() {
        var is = new InputStreamReader(inputStream);
        return new BufferedReader(is)
            .lines()
            .map(SnapshotUtils::of)
            .filter(s -> s.isSnapshot())
            .filter(s -> s.volumeName().equals(volumeId))
            .collect(Collectors.toList());
    }
}

class SpinnerTask implements Callable<Object> {
    private boolean isRunning = true;
    private boolean isPaused = false;
    private String[] animationSymbols = {"⣾", "⣽", "⣻", "⢿", "⡿", "⣟", "⣯", "⣷"};
    private int animationStep = 0;

    @Override
    public Object call() {
        while(isRunning) {
            if (!isPaused) {
                System.out.print("Running " + animationSymbols[animationStep] + "\r");
                animationStep = (++animationStep) % animationSymbols.length;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                stop();
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    public void stop() {
        isRunning = false;
    }

    public void pause() {
        isPaused = true;
    }

    public void resume() {
        isPaused = false;
    }
}
