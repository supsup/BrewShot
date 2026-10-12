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
 * such a file can still render at a size other than the one reported.
 */
final class SvgIntrinsicSize {

    /** Bytes of the file inspected for the root start tag. */
    static final int MAX_PREFIX_BYTES = 1024 * 1024;

    private static final double CSS_PX_PER_INCH = 96.0;

    private static final Pattern LENGTH = Pattern.compile(
        "([+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)([A-Za-z%]*)");

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

    /** A determined size in whole CSS pixels. */
    record Size(int width, int height, Source source) {

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
        Dimension width = dimension("width", attributes.get("width"));
        Dimension height = dimension("height", attributes.get("height"));
        double[] viewBox = viewBox(attributes.get("viewBox"));
        String viewBoxState = attributes.containsKey("viewBox") && viewBox == null
            ? "its viewBox is not four numbers with a positive width and height"
            : "it has no viewBox";

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
        if (value.equalsIgnoreCase("auto")) {
            return Dimension.FILLS_VIEWPORT;
        }
        Matcher matcher = LENGTH.matcher(value);
        if (!matcher.matches()) {
            throw new Undetermined("the root <svg> " + name + " is not a length: \"" + raw + "\"");
        }
        double number;
        try {
            number = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException notANumber) {
            throw new Undetermined("the root <svg> " + name + " is not a length: \"" + raw + "\"");
        }
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        if (unit.equals("%")) {
            if (number == 100.0) {
                return Dimension.FILLS_VIEWPORT;
            }
            throw new Undetermined("the root <svg> " + name + " is a percentage of the "
                + "viewport (\"" + raw + "\"), which has no size of its own");
        }
        double pxPerUnit = switch (unit) {
            case "", "px" -> 1.0;
            case "in" -> CSS_PX_PER_INCH;
            case "cm" -> CSS_PX_PER_INCH / 2.54;
            case "mm" -> CSS_PX_PER_INCH / 25.4;
            case "q" -> CSS_PX_PER_INCH / 101.6;
            case "pt" -> CSS_PX_PER_INCH / 72.0;
            case "pc" -> CSS_PX_PER_INCH / 6.0;
            default -> throw new Undetermined("the root <svg> " + name + " uses the unit \""
                + matcher.group(2) + "\" (\"" + raw + "\"), which only a browser can resolve");
        };
        double px = number * pxPerUnit;
        if (!Double.isFinite(px) || px <= 0) {
            throw new Undetermined("the root <svg> " + name + " is not a positive length: \""
                + raw + "\"");
        }
        return new Dimension(false, px);
    }

    /** {@code {width, height}} of a usable viewBox, or null when absent or unusable. */
    private static double[] viewBox(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split("[\\s,]+");
        if (parts.length != 4) {
            return null;
        }
        double[] numbers = new double[4];
        for (int index = 0; index < 4; index++) {
            Matcher matcher = LENGTH.matcher(parts[index]);
            if (!matcher.matches() || !matcher.group(2).isEmpty()) {
                return null;   // not a bare number (a unit suffix such as "100px" included)
            }
            try {
                numbers[index] = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException notANumber) {
                return null;
            }
            if (!Double.isFinite(numbers[index])) {
                return null;
            }
        }
        if (numbers[2] <= 0 || numbers[3] <= 0) {
            return null;
        }
        return new double[] {numbers[2], numbers[3]};
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
            throw new Undetermined("the root element is <" + qualifiedName + ">, not <svg>");
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
                throw new Undetermined("the root <svg> attribute " + attributeName
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
                throw new Undetermined("the root <svg> attribute " + attributeName
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

    /** Skip to the '>' that closes a DOCTYPE, across quoted literals and an internal subset. */
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
