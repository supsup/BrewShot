package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Session auth read from a file, never from argv (plan 728a0dfd).
 *
 * <p>A value given as {@code --header 'Authorization: Bearer ...'} or {@code --cookie name=value}
 * sits in the process arguments, which any local user can read with {@code ps}. The
 * {@code --header-file} and {@code --cookie-file} flags put only a PATH there. The file must be
 * a regular file (not a symlink) that only its owner can access (mode 0600 or 0400): a file
 * with any group or other permission bit is refused, and the refusal names the mode.
 *
 * <p>No value read from one of these files is ever put into a message this class builds: a
 * refusal names the flag, the path and the line number, never the line. Callers that record
 * the auth (the {@code --json} manifest) write {@link #REDACTED} in place of the value.
 */
final class AuthFile {

    /** What a manifest records in place of a value that came from a file. */
    static final String REDACTED = "<from file>";

    /** An auth file is a handful of lines; anything bigger is a mistake, refused unread. */
    static final int MAX_AUTH_FILE_BYTES = 64 * 1024;

    private static final Set<PosixFilePermission> NOT_OWNER = Set.of(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private AuthFile() { }

    /** One {@code Name: value} line of a {@code --header-file}. */
    record Header(String name, String value) {
        Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        /** Never the value: a record's generated toString would print it. */
        @Override public String toString() { return "Header[" + name + ": " + REDACTED + "]"; }
    }

    /** One {@code name=value[@domain]} line of a {@code --cookie-file}. */
    record Cookie(String name, String value, String domain) {
        Cookie {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(domain, "domain");
        }

        /** Never the value, and never the domain (see {@link #parseCookies}). */
        @Override public String toString() { return "Cookie[" + name + "=" + REDACTED + "]"; }
    }

    /** Read and parse a {@code --header-file}: one {@code Name: value} per line. */
    static List<Header> readHeaders(Path path) throws IOException {
        return parseHeaders(readOwnerOnly(path, "--header-file"), "--header-file " + path);
    }

    /** Read and parse a {@code --cookie-file}: one {@code name=value[@domain]} per line. */
    static List<Cookie> readCookies(Path path) throws IOException {
        return parseCookies(readOwnerOnly(path, "--cookie-file"), "--cookie-file " + path);
    }

    /**
     * Header lines. Blank lines and lines starting with {@code #} are skipped; a trailing CR is
     * dropped. The name is everything before the FIRST colon, the value everything after it
     * (trimmed), so a value may itself contain colons.
     */
    static List<Header> parseHeaders(String text, String source) throws IOException {
        List<Header> headers = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = stripCr(lines[index]);
            if (skippable(line)) { continue; }
            int colon = line.indexOf(':');
            String name = colon < 0 ? "" : line.substring(0, colon).trim();
            String value = colon < 0 ? "" : line.substring(colon + 1).trim();
            if (colon < 0 || name.isEmpty() || value.isEmpty()) {
                throw lineRefusal(source, index + 1, "wants 'Name: value'");
            }
            headers.add(new Header(name, value));
        }
        if (headers.isEmpty()) {
            throw new IOException(source + ": no 'Name: value' line found");
        }
        return headers;
    }

    /**
     * Cookie lines. The name is everything before the first {@code =}. The domain is whatever
     * follows the LAST {@code @} (a domain cannot contain one, a token value can); with no
     * {@code @} it defaults to localhost, as for {@code --cookie}. Because a value containing
     * {@code @} with no domain would split part of the value into the domain, the domain is
     * treated as secret too: no message and no manifest field ever prints it.
     */
    static List<Cookie> parseCookies(String text, String source) throws IOException {
        List<Cookie> cookies = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = stripCr(lines[index]);
            if (skippable(line)) { continue; }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw lineRefusal(source, index + 1, "wants name=value[@domain]");
            }
            String name = line.substring(0, equals).trim();
            String rest = line.substring(equals + 1);
            int at = rest.lastIndexOf('@');
            String value = at < 0 ? rest : rest.substring(0, at);
            String domain = at < 0 ? "localhost" : rest.substring(at + 1).trim();
            if (name.isEmpty() || value.isEmpty() || domain.isEmpty()) {
                throw lineRefusal(source, index + 1, "wants name=value[@domain]");
            }
            cookies.add(new Cookie(name, value, domain));
        }
        if (cookies.isEmpty()) {
            throw new IOException(source + ": no name=value line found");
        }
        return cookies;
    }

    /**
     * The file checks, then the bounded read. Attributes are read WITHOUT following a link, so
     * a symlink is seen as one and refused; the open also refuses to follow a link, so a path
     * swapped for a symlink after the check fails rather than reading the link's target.
     * Messages carry the flag, the path and the mode only.
     */
    static String readOwnerOnly(Path path, String flag) throws IOException {
        Objects.requireNonNull(path, "path");
        String source = flag + " " + path;
        PosixFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new IOException(source + ": no such file");
        } catch (UnsupportedOperationException noPosix) {
            throw new IOException(source + ": this filesystem cannot show whether the file is "
                + "owner-only, so it is refused");
        }
        if (attributes.isSymbolicLink()) {
            throw new IOException(source + ": is a symlink; give the path of the file itself");
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(source + ": is not a regular file");
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.stream().anyMatch(NOT_OWNER::contains)) {
            throw new IOException(source + ": mode " + octal(permissions)
                + " lets group or others access it; it must be owner-only (chmod 600)");
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return BoundedUtf8.readStrict(input, MAX_AUTH_FILE_BYTES, source);
        } catch (FileSystemException unreadable) {
            // The JDK message names the path and the OS reason; it never holds file content.
            String reason = unreadable.getReason();
            throw new IOException(source + ": cannot be read"
                + (reason == null ? "" : " (" + reason + ")"));
        }
    }

    /** The permission set as the four-digit octal a chmod user reads, e.g. {@code 0644}. */
    static String octal(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            mode |= switch (permission) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return String.format("%04o", mode);
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static boolean skippable(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    /** A refusal for one line: names its number, never its text (the text may be a secret). */
    private static IOException lineRefusal(String source, int lineNumber, String wants) {
        return new IOException(source + " line " + lineNumber + ": " + wants
            + " (the line is not shown: it may hold a secret)");
    }
}
