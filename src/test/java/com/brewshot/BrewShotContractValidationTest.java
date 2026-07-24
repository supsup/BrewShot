package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Chrome-free discriminators for the public numeric and escaping contracts. */
class BrewShotContractValidationTest {

    @Test
    void pdfRejectsEveryNonFiniteNumericFieldBeforeRangeChecks() {
        for (double nonFinite : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> BrewShot.PdfOptions.defaults().scale(nonFinite));
            assertThrows(IllegalArgumentException.class,
                () -> BrewShot.PdfOptions.defaults().paper(nonFinite, 11));
            assertThrows(IllegalArgumentException.class,
                () -> BrewShot.PdfOptions.defaults().paper(8.5, nonFinite));
            assertThrows(IllegalArgumentException.class, () -> new BrewShot.PdfOptions(
                false, true, 1, 8.5, 11, nonFinite, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new BrewShot.PdfOptions(
                false, true, 1, 8.5, 11, 0, nonFinite, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new BrewShot.PdfOptions(
                false, true, 1, 8.5, 11, 0, 0, nonFinite, 0));
            assertThrows(IllegalArgumentException.class, () -> new BrewShot.PdfOptions(
                false, true, 1, 8.5, 11, 0, 0, 0, nonFinite));
        }
    }

    @Test
    void clipPreservesFiniteNegativeOriginsAndRequiresPositiveFiniteExtentAndScale() {
        BrewShot.validateClipGeometry(0, 0, 1, 1, 1);
        BrewShot.validateClipGeometry(-12.5, -3.25, 1, 1, 1);
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, Double.NaN, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(Double.NEGATIVE_INFINITY, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, Double.POSITIVE_INFINITY, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, 0, Double.POSITIVE_INFINITY, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, 0, 1, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateClipGeometry(0, 0, 1, 1, Double.NEGATIVE_INFINITY));
    }

    @Test
    void frameRecorderRequiresPositiveCountAndDelays() {
        BrewShot.validateFrameRecorder(1, 1, 1);
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateFrameRecorder(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateFrameRecorder(1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.validateFrameRecorder(1, 1, 0));
    }

    @Test
    void gifDelayRoundsToNearestCentisecondAndRetainsMinimum() {
        assertEquals(20, BrewShot.effectiveGifDelayMs(1));
        assertEquals(20, BrewShot.effectiveGifDelayMs(24));
        assertEquals(30, BrewShot.effectiveGifDelayMs(25));
        assertEquals(70, BrewShot.effectiveGifDelayMs(74));
        assertEquals(80, BrewShot.effectiveGifDelayMs(75));
        assertEquals(80, BrewShot.effectiveGifDelayMs(76));
        assertEquals(655_350, BrewShot.effectiveGifDelayMs(655_354));
        assertThrows(IllegalArgumentException.class, () -> BrewShot.effectiveGifDelayMs(0));
        assertThrows(IllegalArgumentException.class,
            () -> BrewShot.effectiveGifDelayMs(655_355));
    }

    @Test
    void selectorLiteralRoundTripsEveryExecutableStringBoundary() {
        String hostile = "\"quote'\\\\backslash\nline\rreturn\u2028separator\u2029"
            + ");globalThis.__brewshotInjected=true;//";
        String literal = BrewShot.jsStringLiteral(hostile);

        assertEquals(hostile, MiniJson.parse(literal));
        assertTrue(literal.contains("\\\""));
        assertTrue(literal.contains("\\\\"));
        assertTrue(literal.contains("\\n"));
        assertTrue(literal.contains("\\r"));
        assertTrue(literal.contains("\\u2028"));
        assertTrue(literal.contains("\\u2029"));
    }

    @Test
    void invalidViewportFailsBeforeChromeDiscovery() {
        assertThrows(IllegalArgumentException.class, () -> BrewShot.launch(0, 100));
        assertThrows(IllegalArgumentException.class, () -> BrewShot.launch(100, -1));
    }
}
