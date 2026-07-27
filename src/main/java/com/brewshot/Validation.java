package com.brewshot;

/**
 * Canonical contract checks shared by the library and CLI.
 *
 * <p>Floating-point checks always reject non-finite values before applying a
 * range check. Comparisons alone are not sufficient because every comparison
 * with {@code NaN} is false.
 */
final class Validation {

    private Validation() { }

    static int positiveInt(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
        return value;
    }

    static int nonNegativeInt(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
        return value;
    }

    static int intRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                name + " must be " + min + "-" + max + ", got: " + value);
        }
        return value;
    }

    static long positiveLong(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
        return value;
    }

    static long nonNegativeLong(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
        return value;
    }

    static double positiveFinite(String name, double value) {
        finite(name, value);
        if (value <= 0) {
            throw new IllegalArgumentException(
                name + " must be positive and finite, got: " + value);
        }
        return value;
    }

    static double nonNegativeFinite(String name, double value) {
        finite(name, value);
        if (value < 0) {
            throw new IllegalArgumentException(
                name + " must be non-negative and finite, got: " + value);
        }
        return value;
    }

    static double finiteRange(String name, double value, double min, double max) {
        finite(name, value);
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                name + " must be " + min + "-" + max + ", got: " + value);
        }
        return value;
    }

    static double finite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got: " + value);
        }
        return value;
    }
}
