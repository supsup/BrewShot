package com.brewshot;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Commits an artifact through a sibling temporary file.
 *
 * <p>The writer receives a temporary path in the target directory. A complete
 * temporary artifact is moved into place atomically when the filesystem
 * supports it, with a same-directory replace fallback otherwise. Encode/write
 * failures leave an existing target untouched and trigger best-effort temporary
 * cleanup; move failures do the same for any temporary entry that remains. The
 * fallback starts from a complete temporary artifact but cannot guarantee
 * atomic replacement when the filesystem rejects {@code ATOMIC_MOVE}. Existing
 * POSIX permissions are retained across replacement. A valid leaf symlink is
 * followed and its referent replaced, matching {@link Files#write(Path, byte[],
 * java.nio.file.OpenOption...)}, while a broken or cyclic symlink fails before
 * a temporary file is created.
 */
final class ArtifactWriter {

    @FunctionalInterface
    interface TempFileWriter {
        void write(Path temporary) throws IOException;
    }

    @FunctionalInterface
    interface MoveStrategy {
        void move(Path temporary, Path target, boolean atomic) throws IOException;
    }

    private static final MoveStrategy FILESYSTEM_MOVE = (temporary, target, atomic) -> {
        if (atomic) {
            Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    private ArtifactWriter() { }

    /**
     * The lexical file ultimately addressed by an output path after following
     * any leaf-symlink chain. Unlike the write path, a missing final referent is
     * returned so callers can detect aliases between a currently broken link
     * and an output that would make it valid later in the same operation.
     */
    static Path outputIdentity(Path requested) throws IOException {
        return canonicalizeExistingPrefix(resolveLeafSymlinks(requested).path());
    }

    static void write(Path target, TempFileWriter writer) throws IOException {
        write(target, writer, FILESYSTEM_MOVE);
    }

    /** Package-private move seam for the unsupported-atomic-move discriminator. */
    static void write(Path target, TempFileWriter writer, MoveStrategy mover)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(mover, "mover");
        Path commitTarget = resolveWriteTarget(target);
        Path parent = commitTarget.getParent();
        if (parent == null) {
            throw new IOException("output path has no parent: " + target);
        }
        Set<PosixFilePermission> permissions = existingPosixPermissions(commitTarget);
        Path temporary = Files.createTempFile(
            parent, "." + commitTarget.getFileName() + ".", ".tmp");
        boolean committed = false;
        try {
            writer.write(temporary);
            if (permissions != null) {
                Files.setPosixFilePermissions(temporary, permissions);
            }
            moveIntoPlace(temporary, commitTarget, mover);
            committed = true;
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the original write/move failure.
                }
            }
        }
    }

    static void writeBytes(Path target, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        write(target, temporary -> Files.write(temporary, bytes));
    }

    static void writeString(Path target, String value, Charset charset) throws IOException {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(charset, "charset");
        write(target, temporary -> Files.writeString(temporary, value, charset));
    }

    private static void moveIntoPlace(Path temporary, Path target, MoveStrategy mover)
            throws IOException {
        try {
            mover.move(temporary, target, true);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // The artifact is still complete before this fallback begins, but
            // replacement itself is not guaranteed atomic on this filesystem.
            mover.move(temporary, target, false);
        }
    }

    /**
     * Resolve only leaf symlinks. Parent-directory symlinks remain in the path
     * and are followed by the filesystem as usual. Resolving before temp-file
     * creation makes broken/cyclic leaf links a no-write failure.
     */
    private static Path resolveWriteTarget(Path requested) throws IOException {
        ResolvedTarget resolved = resolveLeafSymlinks(requested);
        if (resolved.followedLink()
                && !Files.exists(resolved.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "output symlink is broken: " + requested + " -> " + resolved.path());
        }
        return resolved.path();
    }

    private static ResolvedTarget resolveLeafSymlinks(Path requested) throws IOException {
        Path current = requested.toAbsolutePath().normalize();
        Set<Path> visited = new HashSet<>();
        boolean followedLink = false;
        while (Files.isSymbolicLink(current)) {
            followedLink = true;
            if (!visited.add(current)) {
                throw new IOException("output symlink cycle: " + requested);
            }
            Path linkTarget = Files.readSymbolicLink(current);
            current = linkTarget.isAbsolute()
                ? linkTarget.normalize()
                : current.getParent().resolve(linkTarget).toAbsolutePath().normalize();
        }
        return new ResolvedTarget(current, followedLink);
    }

    private record ResolvedTarget(Path path, boolean followedLink) { }

    /**
     * Resolve symlinked parent directories even when the output leaf does not
     * exist yet. This makes two spellings of the same future output comparable
     * without creating either one.
     */
    private static Path canonicalizeExistingPrefix(Path path) throws IOException {
        Path existing = path;
        java.util.ArrayDeque<Path> missingSuffix = new java.util.ArrayDeque<>();
        while (existing != null
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                missingSuffix.addFirst(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        Path canonical = existing.toRealPath();
        for (Path segment : missingSuffix) {
            canonical = canonical.resolve(segment.toString());
        }
        return canonical.normalize();
    }

    /** Null means this filesystem does not expose POSIX permissions. */
    private static Set<PosixFilePermission> existingPosixPermissions(Path target)
            throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        PosixFileAttributeView view = Files.getFileAttributeView(
            target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view == null
            ? null
            : Set.copyOf(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
    }
}
