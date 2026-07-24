package com.brewshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import org.junit.jupiter.api.Test;

class HeadlessTestEnvironmentTest {

    @Test
    void gradleTestJvmRunsHeadless() {
        assertEquals("true", System.getProperty("java.awt.headless"),
            "the Gradle test task must explicitly set java.awt.headless=true");
        assertTrue(GraphicsEnvironment.isHeadless(),
            "the test JVM must use AWT's headless graphics environment");
    }
}
