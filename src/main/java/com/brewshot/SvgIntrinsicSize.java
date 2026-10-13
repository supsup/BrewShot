package com.brewshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the size a standalone SVG file asks to be drawn at, WITHOUT a browser, so the
 * CLI can size the viewport to the drawing before Chrome starts ({@code --size auto},
 * plan 982dcab9).
 *
 * <p>Only the root {@code <svg>} start tag is read: its {@code width}, {@code height}
 * and {@code viewBox} attributes. The reader is a bounded, hand-written scanner over the
 * first {@link #MAX_PREFIX_BYTES} of the file. It is deliberately not an XML parser: it
 * resolves no entity, fetches no DTD and loads no JDK XML machinery, which keeps the PNG
 * shoot path free of an XXE surface and native-image-clean.
 *
 * <p>The answer is either a concrete pixel size with the rule that produced it, or a
 * named {@link Undetermined} refusal. There is no guessed size: a font-relative length
 * ({@code em}, {@code ex}, ...), a percentage other than 100%, a non-positive value or a
 * missing viewBox each say so rather than degrade to something plausible.
 *
 * <p>Rules, for the root element of a standalone document (where an absent or
 * {@code auto} or {@code 100%} dimension means "fill the viewport"):
 * <ul>
 *   <li>absolute width and absolute height: that size;</li>
 *   <li>neither, with a viewBox: the viewBox size;</li>
 *   <li>one of them, with a viewBox: the missing one from the viewBox aspect ratio;</li>
 *   <li>anything else: {@link Undetermined}.</li>
 * </ul>
 * Fractional results round UP to whole CSS pixels so nothing is clipped.
 *
 * <p>Known limit, stated rather than hidden: a size set only by CSS (a {@code style}
 * attribute or a stylesheet rule on the root) is not seen here. Chrome honours it, so
 * such a file can still render at a size other than the one reported. A {@code style}
 * attribute on the root is at least named: the answer says {@link #STYLE_NOT_READ}.
 *
 * <p>Bounds on file-supplied text: a width, height or viewBox number longer than
 * {@link #MAX_ATTRIBUTE_CHARS} characters is refused before any pattern runs, and every
 * value echoed into a reason goes through {@link #echo}, bounded and with control
 * characters made visible.
 */
final class SvgIntrinsicSize {

    /** Bytes of the file inspected for the root start tag. */
    static final int MAX_PREFIX_BYTES = 1024 * 1024;

    private static final double CSS_PX_PER_INCH = 96.0;

    /**
     * Longest width, height or viewBox number (after trimming) that is matched at all.
     * 64 characters is far beyond any length a drawing states; a longer value is refused
     * by name BEFORE any pattern runs, so an attacker-sized attribute (the reader accepts
     * up to {@link #MAX_PREFIX_BYTES} of root tag) costs a length check, nothing more.
     */
    static final int MAX_ATTRIBUTE_CHARS = 64;

    /** Longest whole viewBox value: four numbers at the bound plus their separators. */
    static final int MAX_VIEWBOX_CHARS = 4 * MAX_ATTRIBUTE_CHARS + 16;

    /** Characters of an attribute value echoed into a refusal reason. */
    static final int MAX_ECHO_CHARS = 32;

    /** Appended to the receipt when the root's {@code style} attribute was ignored. */
    static final String STYLE_NOT_READ = "a style attribute is present and was not read";

    /**
     * A CSS/SVG length: number, optional exponent, optional unit. Every quantifier is
     * possessive and the integer/fraction split is unambiguous, so a match attempt is
     * linear in the input: the earlier {@code [0-9]+\.?[0-9]*} let two digit runs back
     * off against each other, quadratic on a long digit run with one bad character
     * (review brewshot 729 F1). Accepts exactly the same strings as that pattern.
     */
    static final Pattern LENGTH = Pattern.compile(
        "([+-]?+(?:[0-9]++(?:\\.[0-9]*+)?+|\\.[0-9]++)(?:[eE][+-]?+[0-9]++)?+)([A-Za-z%]*+)");

    private SvgIntrinsicSize() { }

    /** Which rule produced the size; {@link #label()} is the stable receipt spelling. */
    enum Source {
        WIDTH_HEIGHT("svg-width-height", "the root width and height"),
        VIEWBOX("svg-viewbox", "the root viewBox"),
        WIDTH_VIEWBOX_ASPECT("svg-width-viewbox-aspect",
            "the root width and the viewBox aspect ratio"),
        HEIGHT_VIEWBOX_ASPECT("svg-height-viewbox-aspect",
            "the root height and the viewBox aspect ratio");

        private final String label;
        private final String description;

        Source(String label, String description) {
            this.label = label;
            this.description = description;
        }

        String label() { return label; }

        String description() { return description; }
    }

    /**
     * A determined size in whole CSS pixels. {@code styleNotRead}: the root carries a
     * {@code style} attribute, which this reader does not interpret and Chrome does.
     */
    record Size(int width, int height, Source source, boolean styleNotRead) {

        Size(int width, int height, Source source) {
            this(width, height, source, false);
        }

        /** The same ceilings verify manifests put on an authored viewport. */
        Size checkedAgainstViewportLimits() throws Undetermined {
            if (width > VerifyManifest.MAX_VIEWPORT_DIMENSION
                    || height > VerifyManifest.MAX_VIEWPORT_DIMENSION
                    || (long) width * height > VerifyManifest.MAX_VIEWPORT_PIXELS) {
                throw new Undetermined("the SVG's own size " + width + "x" + height
                    + " exceeds the viewport limit (" + VerifyManifest.MAX_VIEWPORT_DIMENSION
                    + " per side, " + VerifyManifest.MAX_VIEWPORT_PIXELS + " pixels)");
            }
            return this;
        }
    }

    /** The size could not be determined; the message names the reason. */
    static final class Undetermined extends Exception {
        private static final long serialVersionUID = 1L;

        Undetermined(String reason) {
            super(reason);
        }
    }

    /** Read the root start tag from a bounded prefix of {@code file}. */
    static Size read(Path file) throws IOException, Undetermined {
        byte[] prefix;
        try (InputStream in = Files.newInputStream(file)) {
            prefix = in.readNBytes(MAX_PREFIX_BYTES);
        }
        return parse(decode(prefix));
    }

    /** UTF-8 unless a UTF-16 byte order mark says otherwise; malformed bytes are replaced. */
    private static String decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    /** Parse the (prefix of the) document text. Package-private: the unit-test seam. */
    static Size parse(String text) throws Undetermined {
        Map<String, String> attributes = rootSvgAttributes(text);
        if (!attributes.containsKey("style")) {
            return resolve(attributes);
        }
        // A style attribute can set the size Chrome draws at (review brewshot 729 F3);
        // it is not read here, so every answer says so rather than stay silent.
        Size size;
        try {
            size = resolve(attributes);
        } catch (Undetermined undetermined) {
            throw new Undetermined(undetermined.getMessage() + "; " + STYLE_NOT_READ);
        }
        return new Size(size.width(), size.height(), size.source(), true);
    }

    private static Size resolve(Map<String, String> attributes) throws Undetermined {
        Dimension width = dimension("width", attributes.get("width"));
        Dimension height = dimension("height", attributes.get("height"));
        double[] viewBox = null;
        String viewBoxState = "it has no viewBox";
        if (attributes.containsKey("viewBox")) {
            try {
                viewBox = viewBox(attributes.get("viewBox"));
            } catch (Undetermined unusable) {
                viewBoxState = unusable.getMessage();
            }
        }

        double resolvedWidth;
        double resolvedHeight;
        Source source;
        if (!width.fillsViewport && !height.fillsViewport) {
            resolvedWidth = width.px;
            resolvedHeight = height.px;
            source = Source.WIDTH_HEIGHT;
        } else if (viewBox == null) {
            String missing = width.fillsViewport && height.fillsViewport
                ? "the root <svg> has no absolute width or height"
                : width.fillsViewport ? "the root <svg> has no absolute width"
                : "the root <svg> has no absolute height";
            throw new Undetermined(missing + " and " + viewBoxState);
        } else if (width.fillsViewport && height.fillsViewport) {
            resolvedWidth = viewBox[0];
            resolvedHeight = viewBox[1];
            source = Source.VIEWBOX;
        } else if (height.fillsViewport) {
            resolvedWidth = width.px;
            resolvedHeight = width.px * viewBox[1] / viewBox[0];
            source = Source.WIDTH_VIEWBOX_ASPECT;
        } else {
            resolvedHeight = height.px;
            resolvedWidth = height.px * viewBox[0] / viewBox[1];
            source = Source.HEIGHT_VIEWBOX_ASPECT;
        }
        return new Size(wholePixels("width", resolvedWidth),
            wholePixels("height", resolvedHeight), source)
            .checkedAgainstViewportLimits();
    }

    // ---- lengths ----------------------------------------------------------------

    /** Either an absolute pixel length or "fills the viewport" (absent, auto, 100%). */
    private record Dimension(boolean fillsViewport, double px) {
        static final Dimension FILLS_VIEWPORT = new Dimension(true, Double.NaN);
    }

    private static Dimension dimension(String name, String raw) throws Undetermined {
        if (raw == null) {
            return Dimension.FILLS_VIEWPORT;
        }
        String value = raw.trim();
        if (value.length() > MAX_ATTRIBUTE_CHARS) {
            throw new Undetermined("the root <svg> " + name + " is longer than the "
                + MAX_ATTRIBUTE_CHARS + "-character limit for a length: " + echo(raw));
        }
        if (value.equalsIgnoreCase("auto")) {
            return Dimension.FILLS_VIEWPORT;
        }
        Matcher matcher = LENGTH.matcher(value);
        if (!matcher.matches()) {
            throw new Undetermined("the root <svg> " + name + " is not a length: " + echo(raw));
        }
        double number;
        try {
            number = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException notANumber) {
            throw new Undetermined("the root <svg> " + name + " is not a length: " + echo(raw));
        }
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        if (unit.equals("%")) {
            if (number == 100.0) {
                return Dimension.FILLS_VIEWPORT;
            }
            throw new Undetermined("the root <svg> " + name + " is a percentage of the "
                + "viewport (" + echo(raw) + "), which has no size of its own");
        }
        double pxPerUnit = switch (unit) {
            case "", "px" -> 1.0;
            case "in" -> CSS_PX_PER_INCH;
            case "cm" -> CSS_PX_PER_INCH / 2.54;
            case "mm" -> CSS_PX_PER_INCH / 25.4;
            case "q" -> CSS_PX_PER_INCH / 101.6;
            case "pt" -> CSS_PX_PER_INCH / 72.0;
            case "pc" -> CSS_PX_PER_INCH / 6.0;
            default -> throw new Undetermined("the root <svg> " + name + " uses the unit "
                + echo(matcher.group(2)) + " (" + echo(raw) + "), which only a browser can "
                + "resolve");
        };
        double px = number * pxPerUnit;
        if (!Double.isFinite(px) || px <= 0) {
            throw new Undetermined("the root <svg> " + name + " is not a positive length: "
                + echo(raw));
        }
        return new Dimension(false, px);
    }

    /**
     * {@code {width, height}} of a present viewBox; an unusable one throws, and the message
     * is the clause the caller puts after "and" when it needed the viewBox.
     */
    private static double[] viewBox(String raw) throws Undetermined {
        String unusable = "its viewBox is not four numbers with a positive width and height";
        String value = raw.trim();
        if (value.length() > MAX_VIEWBOX_CHARS) {
            throw new Undetermined("its viewBox is longer than the " + MAX_VIEWBOX_CHARS
                + "-character limit (" + echo(raw) + ")");
        }
        String[] parts = value.split("[\\s,]+");
        if (parts.length != 4) {
            throw new Undetermined(unusable);
        }
        double[] numbers = new double[4];
        for (int index = 0; index < 4; index++) {
            if (parts[index].length() > MAX_ATTRIBUTE_CHARS) {
                throw new Undetermined("its viewBox has a number longer than the "
                    + MAX_ATTRIBUTE_CHARS + "-character limit (" + echo(parts[index]) + ")");
            }
            Matcher matcher = LENGTH.matcher(parts[index]);
            if (!matcher.matches() || !matcher.group(2).isEmpty()) {
                // not a bare number (a unit suffix such as "100px" included)
                throw new Undetermined(unusable);
            }
            try {
                numbers[index] = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException notANumber) {
                throw new Undetermined(unusable);
            }
            if (!Double.isFinite(numbers[index])) {
                throw new Undetermined(unusable);
            }
        }
        if (numbers[2] <= 0 || numbers[3] <= 0) {
            throw new Undetermined(unusable);
        }
        return new double[] {numbers[2], numbers[3]};
    }

    /**
     * A file-supplied value made safe to put in a message that reaches stderr (review
     * brewshot 729 F2): quoted, at most {@link #MAX_ECHO_CHARS} characters followed by an
     * ellipsis and the total length, and every control character (below 0x20, 0x7f-0x9f)
     * and invisible format/separator character (bidi overrides, zero-width, U+2028/9)
     * replaced by a visible {@code \xNN} (or backslash-u four-hex-digit) escape, so a
     * file cannot write terminal escape sequences or a multi-megabyte line through a
     * refusal reason.
     */
    static String echo(String raw) {
        return visible(raw, "\"");
    }

    /**
     * {@link #echo} without the quotes, for a name the reason already brackets (the root
     * element's {@code <name>}, an attribute name). Same bound, same escapes.
     */
    static String echoName(String raw) {
        return visible(raw, "");
    }

    private static String visible(String raw, String quote) {
        int keep = raw.length();
        boolean truncated = keep > MAX_ECHO_CHARS;
        if (truncated) {
            keep = MAX_ECHO_CHARS;
            if (Character.isHighSurrogate(raw.charAt(keep - 1))) {
                keep--;   // never cut a surrogate pair in half
            }
        }
        StringBuilder out = new StringBuilder(keep + 32).append(quote);
        for (int index = 0; index < keep; index++) {
            char character = raw.charAt(index);
            int type = Character.getType(character);
            if (character < 0x20 || (character >= 0x7f && character <= 0x9f)) {
                out.append(String.format(Locale.ROOT, "\\x%02x", (int) character));
            } else if (type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || (Character.isSurrogate(character)
                        && !(Character.isHighSurrogate(character) && index + 1 < keep
                            && Character.isLowSurrogate(raw.charAt(index + 1)))
                        && !(Character.isLowSurrogate(character) && index > 0
                            && Character.isHighSurrogate(raw.charAt(index - 1))))) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                out.append(character);
            }
        }
        out.append(quote);
        if (truncated) {
            out.append("... (").append(raw.length()).append(" characters)");
        }
        return out.toString();
    }

    private static int wholePixels(String name, double px) throws Undetermined {
        // Round up so a 793.7px drawing is not clipped; the epsilon keeps a product such
        // as 1000 * 780 / 1000 that lands a hair above an integer from gaining a pixel.
        double rounded = Math.ceil(px - 1e-6);
        if (!Double.isFinite(rounded) || rounded > Integer.MAX_VALUE) {
            throw new Undetermined("the SVG " + name + " is too large to be a viewport: " + px);
        }
        return (int) Math.max(1.0, rounded);
    }

    // ---- root start tag -----------------------------------------------------------

    /**
     * The unprefixed attributes of the root element, which must be {@code svg}. Skips the
     * XML declaration, processing instructions, comments and a DOCTYPE (with an internal
     * subset) on the way. First spelling of a repeated attribute wins.
     */
    private static Map<String, String> rootSvgAttributes(String text) throws Undetermined {
        int position = !text.isEmpty() && text.charAt(0) == 0xFEFF ? 1 : 0;
        int length = text.length();
        while (true) {
            while (position < length && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            if (position >= length) {
                throw new Undetermined(length == 0 ? "the file is empty"
                    : "no root element starts within the first " + MAX_PREFIX_BYTES + " bytes");
            }
            if (text.startsWith("<?", position)) {
                position = after(text, "?>", position + 2, "processing instruction");
            } else if (text.startsWith("<!--", position)) {
                position = after(text, "-->", position + 4, "comment");
            } else if (text.startsWith("<!DOCTYPE", position)) {
                position = afterDoctype(text, position + 9);
            } else if (text.charAt(position) == '<' && position + 1 < length
                    && isNameStart(text.charAt(position + 1))) {
                break;
            } else {
                throw new Undetermined("the file does not start with an XML element");
            }
        }

        int nameStart = position + 1;
        int cursor = nameStart;
        while (cursor < length && isNameChar(text.charAt(cursor))) {
            cursor++;
        }
        String qualifiedName = text.substring(nameStart, cursor);
        String localName = qualifiedName.substring(qualifiedName.lastIndexOf(':') + 1);
        if (!localName.equals("svg")) {
            throw new Undetermined("the root element is <" + echoName(qualifiedName)
                + ">, not <svg>");
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        while (true) {
            while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                throw unclosedRoot();
            }
            char next = text.charAt(cursor);
            if (next == '>') {
                return attributes;
            }
            if (next == '/') {
                if (cursor + 1 < length && text.charAt(cursor + 1) == '>') {
                    return attributes;
                }
                if (cursor + 1 >= length) {
                    throw unclosedRoot();
                }
                throw new Undetermined("the root <svg> start tag is malformed");
            }
            if (!isNameStart(next)) {
                throw new Undetermined("the root <svg> start tag is malformed");
            }
            int attributeStart = cursor;
            while (cursor < length && isNameChar(text.charAt(cursor))) {
                cursor++;
            }
            String attributeName = text.substring(attributeStart, cursor);
            while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                throw unclosedRoot();
            }
            if (text.charAt(cursor) != '=') {
                throw new Undetermined("the root <svg> attribute " + echoName(attributeName)
                    + " has no value");
            }
            cursor++;
            while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= length) {
                throw unclosedRoot();
            }
            char quote = text.charAt(cursor);
            if (quote != '"' && quote != '\'') {
                throw new Undetermined("the root <svg> attribute " + echoName(attributeName)
                    + " is not quoted");
            }
            int valueEnd = text.indexOf(quote, cursor + 1);
            if (valueEnd < 0) {
                throw unclosedRoot();
            }
            attributes.putIfAbsent(attributeName, text.substring(cursor + 1, valueEnd));
            cursor = valueEnd + 1;
        }
    }

    private static Undetermined unclosedRoot() {
        return new Undetermined("the root <svg> start tag does not end within the first "
            + MAX_PREFIX_BYTES + " bytes");
    }

    private static int after(String text, String terminator, int from, String what)
            throws Undetermined {
        int end = text.indexOf(terminator, from);
        if (end < 0) {
            throw new Undetermined("a " + what + " before the root element does not end "
                + "within the first " + MAX_PREFIX_BYTES + " bytes");
        }
        return end + terminator.length();
    }

    /**
     * Skip to the '>' that closes a DOCTYPE, across quoted literals and an internal subset.
     *
     * <p>Comments and processing instructions are opaque spans, skipped whole BEFORE any
     * quote or bracket is looked at: their text is not markup, so an apostrophe in
     * {@code <?pi it's ?>} must not open a "literal" that swallows the real root and lets a
     * later {@code <svg>} stand in for it (review brewshot 729 F5: a 100x50 root was read
     * as 9x9). Quote tracking still applies to the remaining text, where a quoted entity
     * value may hold a {@code ]} or {@code >}. A span or literal that does not close within
     * the prefix is a named refusal, never a size.
     */
    private static int afterDoctype(String text, int from) throws Undetermined {
        int depth = 0;
        int cursor = from;
        int length = text.length();
        while (cursor < length) {
            char current = text.charAt(cursor);
            if (text.startsWith("<!--", cursor)) {
                cursor = after(text, "-->", cursor + 4, "comment");
                continue;
            }
            if (text.startsWith("<?", cursor)) {
                cursor = after(text, "?>", cursor + 2, "processing instruction");
                continue;
            }
            if (current == '"' || current == '\'') {
                int close = text.indexOf(current, cursor + 1);
                if (close < 0) {
                    break;
                }
                cursor = close + 1;
                continue;
            }
            if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
            } else if (current == '>' && depth <= 0) {
                return cursor + 1;
            }
            cursor++;
        }
        throw new Undetermined("a DOCTYPE before the root element does not end within the "
            + "first " + MAX_PREFIX_BYTES + " bytes");
    }

    private static boolean isNameStart(char character) {
        return Character.isLetter(character) || character == '_' || character == ':';
    }

    private static boolean isNameChar(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == ':'
            || character == '-' || character == '.';
    }
}
