package com.brewshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Startup CLEANUP of other BrewShot profiles whose Chrome has provably exited
 * (plan 3a45f8fe, follow-up to the bad1387c leak measurement).
 *
 * <p>This is NOT a release proof and must never be wired as one. A launch whose
 * release is unproven keeps its profile, by decision; that profile is then left
 * for a LATER launch to find here, once it is old and its lock names a dead
 * Chrome. Nothing in this class makes any launch's own release claim truer.
 *
 * <p>A candidate is a direct sibling of this launch's own profile, named exactly
 * like {@link Files#createTempDirectory} names ours ({@code brewshot-<digits>}).
 * It is deleted only when EVERY check below holds, and kept when any one of
 * them fails or cannot be answered:
 * <ul>
 *   <li>it is a plain directory, not a symlink;</li>
 *   <li>its owner uid equals the uid that owns this launch's own profile;</li>
 *   <li>it was last modified at least {@link #MIN_AGE} ago;</li>
 *   <li>its Chrome {@code SingletonLock} is a symlink to {@code <host>-<pid>},
 *       the host is the one this launch's own Chrome wrote into its own lock,
 *       and that pid does not exist in this process namespace;</li>
 *   <li>no process this JVM can see carries the profile path in its argv.</li>
 * </ul>
 *
 * <p>Our own live profile is never a candidate, and when our own lock cannot be
 * read nothing is reaped at all: without it there is no host to compare. The
 * lock is deleted LAST, so a delete that fails halfway leaves a directory that
 * still carries its dead-owner lock and is retried by the next launch, instead
 * of a lockless remnant that every later check would keep forever.
 */
final class StaleProfileReaper {

    /** A profile must be at least this old. A teardown takes seconds; this is hours of margin. */
    static final Duration MIN_AGE = Duration.ofHours(1);

    /** At most this many directories are deleted per launch, so one launch never stalls on a backlog. */
    static final int MAX_REAPED_PER_RUN = 32;

    static final String LOCK = "SingletonLock";

    private static final Pattern PROFILE_NAME = Pattern.compile("brewshot-[0-9]+");
    private static final Pattern PID = Pattern.compile("[0-9]{1,10}");

    /** True only when {@code pid} provably does not exist; unknown is false. */
    interface Liveness {
        boolean provablyDead(long pid);
    }

    /** True when some visible process names {@code profile} in its argv. */
    interface ArgvProbe {
        boolean anyProcessNames(Path profile);
    }

    /** The numeric owner uid of {@code path}, without following a link; throws when unknown. */
    interface OwnerUid {
        int of(Path path) throws IOException;
    }

    /** What one run did: every reaped path, and every examined path it kept with the reason. */
    record Outcome(List<Path> reaped, Map<Path, String> kept) { }

    private StaleProfileReaper() { }

    /**
     * Runs the reaper beside a freshly launched profile with the real probes, and
     * never throws: any fault is a reason to keep, not to fail the launch.
     * Prints one stderr line only when something was removed.
     */
    static void reapBeside(Path ownProfile) {
        try {
            Outcome outcome = reap(ownProfile, Instant.now(), MIN_AGE,
                StaleProfileReaper::processProvablyDead, new LiveArgvProbe(),
                StaleProfileReaper::unixUid);
            if (!outcome.reaped().isEmpty()) {
                System.err.println("brewshot: removed " + outcome.reaped().size()
                    + " stale profile(s) whose Chrome had exited"
                    + " (startup cleanup, not a release proof)");
            }
        } catch (RuntimeException | Error ignored) {
            // Cleanup is best effort; a launch never fails because of it.
        }
    }

    static Outcome reap(Path ownProfile, Instant now, Duration minAge,
                        Liveness liveness, ArgvProbe argv, OwnerUid owner) {
        List<Path> reaped = new ArrayList<>();
        Map<Path, String> kept = new LinkedHashMap<>();
        Path dir = ownProfile.getParent();
        String ownName = String.valueOf(ownProfile.getFileName());
        String ownHost = lockHost(ownProfile);
        int ownUid;
        if (dir == null || ownHost == null) { return new Outcome(reaped, kept); }
        try { ownUid = owner.of(ownProfile); }
        catch (IOException | RuntimeException unknown) { return new Outcome(reaped, kept); }

        FileTime newest = FileTime.from(now.minus(minAge));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path candidate : entries) {
                String name = String.valueOf(candidate.getFileName());
                if (!PROFILE_NAME.matcher(name).matches() || name.equals(ownName)) { continue; }
                if (reaped.size() >= MAX_REAPED_PER_RUN) {
                    kept.put(candidate, "per-run limit reached");
                    continue;
                }
                String refusal = refusal(candidate, ownHost, ownUid, newest, liveness, argv, owner);
                if (refusal != null) {
                    kept.put(candidate, refusal);
                } else if (deleteLockLast(candidate)) {
                    reaped.add(candidate);
                } else {
                    kept.put(candidate, "delete incomplete");
                }
            }
        } catch (IOException | RuntimeException listing) {
            // What was reaped before the fault stays reaped; the rest waits for the next launch.
        }
        return new Outcome(reaped, kept);
    }

    /** Null when every check holds; otherwise why the candidate is kept. */
    private static String refusal(Path candidate, String ownHost, int ownUid, FileTime newest,
                                  Liveness liveness, ArgvProbe argv, OwnerUid owner) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(
                candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException unreadable) {
            return "attributes unreadable";
        }
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) { return "not a plain directory"; }
        try {
            if (owner.of(candidate) != ownUid) { return "owned by another uid"; }
        } catch (IOException | RuntimeException unknown) {
            return "owner unreadable";
        }
        if (attrs.lastModifiedTime().compareTo(newest) > 0) { return "younger than the minimum age"; }

        String target = lockTarget(candidate);
        if (target == null) { return "no readable lock"; }
        int dash = target.lastIndexOf('-');
        if (dash <= 0 || !PID.matcher(target.substring(dash + 1)).matches()) {
            return "lock is not <host>-<pid>";
        }
        if (!target.substring(0, dash).equals(ownHost)) { return "lock names another host"; }
        long pid = Long.parseLong(target.substring(dash + 1));
        boolean dead;
        try { dead = pid > 0 && liveness.provablyDead(pid); }
        catch (RuntimeException unknown) { dead = false; }
        if (!dead) { return "lock owner alive or unknown"; }
        boolean named;
        try { named = argv.anyProcessNames(candidate); }
        catch (RuntimeException unknown) { named = true; }
        if (named) { return "a running process names this profile"; }
        return null;
    }

    /** The host half of a profile's own lock, or null when it is not {@code <host>-<pid>}. */
    private static String lockHost(Path profile) {
        String target = lockTarget(profile);
        if (target == null) { return null; }
        int dash = target.lastIndexOf('-');
        if (dash <= 0 || !PID.matcher(target.substring(dash + 1)).matches()) { return null; }
        return target.substring(0, dash);
    }

    private static String lockTarget(Path profile) {
        try {
            Path lock = profile.resolve(LOCK);
            if (!Files.isSymbolicLink(lock)) { return null; }
            return Files.readSymbolicLink(lock).toString();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Deletes everything under {@code profile} except its top-level lock, then the
     * lock, then the directory. Links are removed, never followed. True only when
     * the directory is gone afterwards.
     */
    static boolean deleteLockLast(Path profile) {
        Path lock = profile.resolve(LOCK);
        try {
            Files.walkFileTree(profile, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.equals(lock)) {
                        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (!d.equals(profile)) {
                        try { Files.deleteIfExists(d); } catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            // Only when the lock is ALL that is left: deleting it beside a survivor
            // would turn a retryable remnant into a lockless one kept forever.
            if (!onlyTheLockRemains(profile, lock)) { return false; }
            Files.deleteIfExists(lock);
            Files.deleteIfExists(profile);
        } catch (IOException | RuntimeException incomplete) {
            // Reported by the existence check below.
        }
        try { return Files.notExists(profile, LinkOption.NOFOLLOW_LINKS); }
        catch (RuntimeException unknown) { return false; }
    }

    private static boolean onlyTheLockRemains(Path profile, Path lock) throws IOException {
        try (DirectoryStream<Path> left = Files.newDirectoryStream(profile)) {
            for (Path entry : left) {
                if (!entry.equals(lock)) { return false; }
            }
        }
        return true;
    }

    static boolean processProvablyDead(long pid) {
        try { return ProcessHandle.of(pid).isEmpty(); }
        catch (RuntimeException unknown) { return false; }
    }

    static int unixUid(Path path) throws IOException {
        try {
            Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (uid instanceof Integer value) { return value; }
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            throw new IOException("no unix uid on this platform", unsupported);
        }
        throw new IOException("unix:uid is not an integer");
    }

    /** Reads every visible command line once per run, then answers from that snapshot. */
    static final class LiveArgvProbe implements ArgvProbe {
        private List<String> commandLines;

        @Override
        public boolean anyProcessNames(Path profile) {
            String needle = profile.toAbsolutePath().toString();
            if (commandLines == null) {
                List<String> lines = new ArrayList<>();
                ProcessHandle self = ProcessHandle.current();
                try (var all = ProcessHandle.allProcesses()) {
                    all.filter(ph -> !ph.equals(self))
                        .forEach(ph -> {
                            try { ph.info().commandLine().ifPresent(lines::add); }
                            catch (RuntimeException ignored) { }
                        });
                }
                commandLines = lines;
            }
            for (String line : commandLines) {
                if (line.contains(needle)) { return true; }
            }
            return false;
        }
    }
}
