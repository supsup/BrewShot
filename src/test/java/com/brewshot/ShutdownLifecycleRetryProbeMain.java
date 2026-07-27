package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Pure-Java subprocess body proving that one real Runtime shutdown-hook
 * invocation reconciles both a first force-reap failure and a first delete
 * failure when the controlled fake supplies external containment proof. An
 * optional {@code unproven} mode uses the production-default closed release
 * gate and proves the profile survives JVM exit. Its receipt is deliberately
 * outside the profile.
 */
public final class ShutdownLifecycleRetryProbeMain {

    private ShutdownLifecycleRetryProbeMain() { }

    public static void main(String[] args) throws Exception {
        Path profile = Path.of(args[0]);
        Path receipt = Path.of(args[1]);
        Files.createDirectory(profile);
        Files.writeString(profile.resolve("marker"), "owned by fake Chrome");

        RetryProcess process = new RetryProcess(receipt);
        AtomicInteger deleteCalls = new AtomicInteger();
        BrewShot.ProfileDeleter deleter = path -> {
            int call = deleteCalls.incrementAndGet();
            append(receipt, "delete=" + call);
            if (call == 1) {
                throw new IllegalStateException("injected first delete failure");
            }
            try {
                Files.deleteIfExists(path.resolve("marker"));
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        if (args.length > 2 && "unproven".equals(args[2])) {
            BrewShot.registerLaunchLease(process, profile, deleter);
        } else {
            BrewShot.registerContainedLaunchLeaseForTests(
                process, profile, deleter);
        }
        // Normal return invokes BrewShot's real Runtime shutdown hook.
    }

    private static void append(Path receipt, String line) {
        try {
            Files.writeString(receipt, line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RetryProcess extends Process {
        private final Path receipt;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger forceCalls = new AtomicInteger();
        private final RetryHandle handle = new RetryHandle(alive);

        RetryProcess(Path receipt) {
            this.receipt = receipt;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (alive.get()) { Thread.sleep(1); }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) { throw new IllegalThreadStateException("still alive"); }
            return 0;
        }

        @Override
        public void destroy() { }

        @Override
        public Process destroyForcibly() {
            int call = forceCalls.incrementAndGet();
            append(receipt, "force=" + call);
            if (call >= 2) { handle.exitNow(); }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    private static final class RetryHandle implements ProcessHandle {
        private final AtomicBoolean alive;
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();

        RetryHandle(AtomicBoolean alive) {
            this.alive = alive;
        }

        @Override
        public long pid() {
            return 909_090;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return exited;
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }

        void exitNow() {
            alive.set(false);
            exited.complete(this);
        }
    }
}
