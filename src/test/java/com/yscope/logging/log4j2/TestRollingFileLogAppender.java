package com.yscope.logging.log4j2;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class TestRollingFileLogAppender {
    private static final PatternLayout patternLayout = PatternLayout.newBuilder().withPattern(
            "%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n"
    ).build();
    private static final int flushFatalLevelTimeout = 1;
    private static final int flushErrorLevelTimeout = 2;
    private static final int flushInfoLevelTimeout = 3;
    private static final int flushSoftTimeoutUnitInMilliseconds = 1000;
    private static final int flushHardTimeoutUnitInMilliseconds = 60000;
    private static final int timeoutCheckPeriod = 10;
    private static final String outputDir = "testOutputDir";

    /**
     * Tests rollover based on the uncompressed size of the file.
     */
    @Test
    public void testRollingBasedOnUncompressedSize() {
        RollingFileTestAppender appender = createTestAppender(true, true);
        // Set the uncompressed rollover size to 1 so that every append triggers a rollover
        appender._setRolloverUncompressedSizeThreshold(1);
        appender.start();

        // Verify rollover after appending every event
        int expectedNumRollovers = 0;
        appendLogEvent(0, Level.INFO, appender);
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
        appendLogEvent(0, Level.INFO, appender);
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
    }

    /**
     * Tests rollover based on the compressed size of the file.
     */
    @Test
    public void testRollingBasedOnCompressedSize() {
        RollingFileTestAppender appender = createTestAppender(true, true);

        // Set the compressed rollover size to 1 so that a rollover is triggered once data is output
        // to the file
        appender._setRolloverCompressedSizeThreshold(1);
        appender.start();

        // Verify that an append-flush-append sequence triggers a rollover. We need the first append
        // and flush to force the compressor to flush the buffered log event to the output file. The
        // final append is to trigger the rollover.
        int expectedNumRollovers = 0;
        appendLogEvent(0, Level.INFO, appender);
        assertDoesNotThrow(appender::flush);
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
        appendLogEvent(0, Level.INFO, appender);
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, 0, expectedNumRollovers);
    }

    /**
     * Tests the hard timeout
     */
    @Test
    public void testHardTimeout() {
        validateBasicFlushTimeoutSupport(false);

        RollingFileTestAppender appender = createTestAppender(true, false);
        appender.start();
        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;
        int currentTimestamp = 1;

        // Verify a sequence of two ERROR events triggers a sync due to the hard timeout of the
        // first ERROR event
        appendLogEvent(currentTimestamp, Level.ERROR, appender);

        // Move time forward to just before the timeout
        // NOTE: We use "- 2" here (instead of "- 1") so that in the next validation step,
        // validateSyncAfterTimeout still has room to move time forward before triggering the
        // timeout
        currentTimestamp += flushErrorLevelTimeout * flushHardTimeoutUnitInMilliseconds - 2;
        appender.setTime(currentTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

        // Append the second ERROR event and validate a sync happens due to the first
        appendLogEvent(currentTimestamp, Level.ERROR, appender);
        currentTimestamp += 2;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Validate no sync happens because of the second ERROR event
        currentTimestamp += flushErrorLevelTimeout * flushHardTimeoutUnitInMilliseconds;
        appender.setTime(currentTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Tests the soft timeout
     */
    @Test
    public void testSoftTimeout() {
        RollingFileTestAppender appender = createTestAppender(false, true);
        appender.start();
        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;
        int currentTimestamp = 1;

        // Append three events over some time period and verify a sync only happens after the
        // timeout triggered by the last event
        int iterations = 3;
        for (int i = 0; i < iterations; i++) {
            appendLogEvent(currentTimestamp, Level.INFO, appender);
            currentTimestamp += 1;
            appender.setTime(currentTimestamp);
        }
        // NOTE: The -1 is to account for the extra time unit we added after the last log event
        currentTimestamp += flushInfoLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Verify a sequence of two ERROR events triggers a sync due to the soft timeout of the
        // second ERROR event
        appendLogEvent(currentTimestamp, Level.ERROR, appender);

        // Move time forward to just before the timeout
        currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
        appender.setTime(currentTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

        // Append the second ERROR event and validate a sync happens only due to the second
        appendLogEvent(currentTimestamp, Level.ERROR, appender);
        currentTimestamp += 1;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
        currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Verify a sequence of ERROR-INFO events triggers a sync due to the soft timeout of the
        // second log event as if it was an ERROR event rather than an INFO event
        appendLogEvent(currentTimestamp, Level.ERROR, appender);

        // Move time forward to just before the timeout
        currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
        appender.setTime(currentTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

        // Append the INFO event and validate the timeout logic treats it as if it was a second
        // ERROR event
        appendLogEvent(currentTimestamp, Level.INFO, appender);
        currentTimestamp += 1;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
        currentTimestamp += flushErrorLevelTimeout * flushSoftTimeoutUnitInMilliseconds - 1;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Tests custom log levels with the hard timeouts
     */
    @Test
    public void testCustomLogLevelsWithHardTimeout() {
        validateFlushTimeoutSupportForCustomLogLevels(false);
    }

    /**
     * Tests custom log levels with the soft timeouts
     */
    @Test
    public void testCustomLogLevelWithSoftTimeout() {
        validateFlushTimeoutSupportForCustomLogLevels(true);
    }

    /**
     * Tests closing the appender with different closeOnShutdown settings
     */
    @Test
    public void testClose() {
        validateAppenderClose(true);
        validateAppenderClose(false);
    }

    /**
     * Tests the appender's shutdown handling when it's open/closed
     */
    @Test
    public void testShutdownLogic() {
        validateCloseBeforeShutdown(true);
        validateCloseBeforeShutdown(false);
        validateShutdownWithoutClose(0);
        validateShutdownWithoutClose(1);
        validateShutdownWithoutClose(2);
    }

    @AfterEach
    public void cleanUpFiles() {
        // Delete the output directory tree
        try (Stream<Path> s = Files.walk(Paths.get(outputDir))) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ex) {
            fail(ex);
        }
    }

    /**
     * Performs basic validation of flush timeout support (not specific to either soft/hard) for the
     * appender
     * 
     * @param testSoftTimeout Whether to test soft (true) or hard (false) timeout support
     */
    private void validateBasicFlushTimeoutSupport(boolean testSoftTimeout) {
        int timeoutUnitInMilliseconds = testSoftTimeout ? flushSoftTimeoutUnitInMilliseconds
                : flushHardTimeoutUnitInMilliseconds;
        RollingFileTestAppender appender = createTestAppender(
                false == testSoftTimeout,
                testSoftTimeout
        );
        appender.start();
        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;
        int currentTimestamp = 1;

        // Verify a single INFO event triggers a sync after a timeout
        appendLogEvent(currentTimestamp, Level.INFO, appender);
        currentTimestamp += flushInfoLevelTimeout * timeoutUnitInMilliseconds;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Verify a sequence of INFO-ERROR events triggers a sync due to the ERROR event sooner than
        // the timeout for the INFO event
        appendLogEvent(currentTimestamp, Level.INFO, appender);
        appendLogEvent(currentTimestamp, Level.ERROR, appender);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
        currentTimestamp += flushErrorLevelTimeout * timeoutUnitInMilliseconds;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Validate no sync happens because of the INFO event
        currentTimestamp += flushInfoLevelTimeout * timeoutUnitInMilliseconds;
        appender.setTime(currentTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Performs basic validation of flush timeout support for custom log levels (not specific to
     * either soft/hard timeouts)
     * 
     * @param testSoftTimeout Whether to test soft (true) or hard (false) timeout support
     */
    private void validateFlushTimeoutSupportForCustomLogLevels(boolean testSoftTimeout) {
        int timeoutUnitInMilliseconds = testSoftTimeout ? flushSoftTimeoutUnitInMilliseconds
                : flushHardTimeoutUnitInMilliseconds;
        RollingFileTestAppender appender = createTestAppender(
                false == testSoftTimeout,
                testSoftTimeout
        );
        appender.start();
        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;
        int currentTimestamp = 1;

        // Verify an event with a custom log level can be handled and is assigned the INFO level
        // timeout
        appendLogEvent(
                currentTimestamp,
                Level.forName("DEBUG1", Level.DEBUG.intLevel() + 1),
                appender
        );
        currentTimestamp += flushInfoLevelTimeout * timeoutUnitInMilliseconds;
        ++expectedNumSyncs;
        validateSyncAfterTimeout(
                currentTimestamp,
                expectedNumSyncs,
                expectedNumRollovers,
                appender
        );

        // Verify a rollover after closing the appender
        appender.stop();
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Validates the flush and sync logic when the appender is closed
     * 
     * @param closeOnShutdown The value of closeOnShutdown to use when validating
     */
    private void validateAppenderClose(boolean closeOnShutdown) {
        RollingFileTestAppender appender = createTestAppender(false, false);
        appender._setCloseOnShutdown(closeOnShutdown);
        appender.start();
        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;

        assertTrue(appender.backgroundThreadsRunning());

        // Close the appender and verify the state of the background threads
        appender.stop();
        assertFalse(appender.backgroundThreadsRunning());

        // Verify a rollover after closing the appender
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Validates closing the appender and then shutting it down
     * 
     * @param closeOnShutdown The value of closeOnShutdown to use when validating
     */
    private void validateCloseBeforeShutdown(boolean closeOnShutdown) {
        RollingFileTestAppender appender = createTestAppender(false, false);
        appender._setCloseOnShutdown(closeOnShutdown);
        appender.start();

        assertTrue(appender.backgroundThreadsRunning());

        appender.stop();
        assertFalse(appender.backgroundThreadsRunning());

        appender.simulateShutdownHook();
        waitForBackgroundFlushThread();
        assertFalse(appender.backgroundThreadsRunning());
    }

    /**
     * Validates shutting down the appender without calling {@code close} first
     * 
     * @param numTimeoutSettingsToTest 0 - no timeouts, 1 - soft timeout only, 2 - both soft and
     * hard timeouts
     */
    private void validateShutdownWithoutClose(int numTimeoutSettingsToTest) {
        RollingFileTestAppender appender = createTestAppender(true, true);
        appender._setCloseOnShutdown(false);
        final int shutdownSoftTimeoutInMillis = 200;
        appender._setShutdownSoftTimeout(shutdownSoftTimeoutInMillis);
        final int numShutdownSoftTimeoutsInHardTimeout = 5;
        appender._setShutdownHardTimeout(
                shutdownSoftTimeoutInMillis * numShutdownSoftTimeoutsInHardTimeout / 1000
        );
        appender.start();

        int expectedNumSyncs = 0;
        int expectedNumRollovers = 0;
        int currentTimestamp = 0;

        assertTrue(appender.backgroundThreadsRunning());

        // Trigger the shutdown hook and ensure the threads continue to run
        appender.simulateShutdownHook();
        waitForBackgroundFlushThread();
        assertTrue(appender.backgroundThreadsRunning());

        if (0 == numTimeoutSettingsToTest) {
            // Don't log anything
            currentTimestamp = shutdownSoftTimeoutInMillis - 1;
        } else if (1 == numTimeoutSettingsToTest) {
            // Log two events to advance the soft shutdown timeout once before it expires. In each
            // case, ensure the background threads continue to run.
            for (int i = 0; i < 2; ++i) {
                appendLogEvent(currentTimestamp, Level.INFO, appender);
                waitForBackgroundFlushThread();

                currentTimestamp += shutdownSoftTimeoutInMillis - 1;
                setTimestampAndValidateThreadsState(currentTimestamp, true, appender);
            }
        } else {
            // Log enough events so that we get close to the hard shutdown timeout without exceeding
            // it, all while the soft shutdown timeout is kept alive. Throughout, ensure the
            // background threads continue to run.
            for (int i = 0; i < numShutdownSoftTimeoutsInHardTimeout; ++i) {
                appendLogEvent(currentTimestamp, Level.INFO, appender);
                waitForBackgroundFlushThread();

                currentTimestamp += shutdownSoftTimeoutInMillis - 1;
                setTimestampAndValidateThreadsState(currentTimestamp, true, appender);
            }
            // Log one more event so that we exceed the hard shutdown timeout and advance the
            // timestamp to just before the hard timeout
            appendLogEvent(currentTimestamp, Level.INFO, appender);
            waitForBackgroundFlushThread();
            currentTimestamp = shutdownSoftTimeoutInMillis * numShutdownSoftTimeoutsInHardTimeout
                    - 1;
        }

        // Validate that the threads are running up until the timeout expires
        setTimestampAndValidateThreadsState(currentTimestamp, true, appender);
        currentTimestamp += 1;
        setTimestampAndValidateThreadsState(currentTimestamp, false, appender);

        // Verify a rollover after closing the appender
        ++expectedNumRollovers;
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    private void appendLogEvent(long timestamp, Level level, RollingFileTestAppender appender) {
        String loggerName = TestFileAppender.class.getCanonicalName();
        String message = "Static text, dictVar1, 123, 456.7, dictVar2, 987, 654.3";
        LogEvent event = Log4jLogEvent.newBuilder().setLoggerName(loggerName).setLevel(level)
                .setMessage(new SimpleMessage(message)).setThreadName(
                        Thread.currentThread().getName()
                ).setTimeMillis(timestamp).build();
        appender.append(event);
    }

    /**
     * Validates that a sync only occurs after the specified timestamp and not a time unit before
     * 
     * @param syncTimestamp Time when the sync should occur
     * @param expectedNumSyncs
     * @param expectedNumRollovers
     * @param appender
     */
    private void validateSyncAfterTimeout(
            long syncTimestamp,
            int expectedNumSyncs,
            int expectedNumRollovers,
            RollingFileTestAppender appender
    ) {
        appender.setTime(syncTimestamp - 1);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs - 1, expectedNumRollovers);
        appender.setTime(syncTimestamp);
        validateNumSyncAndCloseEvents(appender, expectedNumSyncs, expectedNumRollovers);
    }

    /**
     * Sets the appender's time to the given timestamp and validates that the threads are in the
     * given state
     * 
     * @param timestamp
     * @param threadsShouldBeRunning
     * @param appender
     */
    private void setTimestampAndValidateThreadsState(
            long timestamp,
            boolean threadsShouldBeRunning,
            RollingFileTestAppender appender
    ) {
        appender.setTime(timestamp);
        waitForBackgroundFlushThread();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (appender.backgroundThreadsRunning() != threadsShouldBeRunning) {}
        });
    }

    /**
     * Imperfectly waits for the appender's background flush thread to make progress by simply
     * sleeping for some amount of time
     */
    private void waitForBackgroundFlushThread() {
        final long durationNecessaryForBackgroundFlushThreadProgress = 200; // milliseconds
        assertDoesNotThrow(() -> sleep(durationNecessaryForBackgroundFlushThreadProgress));
    }

    /**
     * Validates that the appender has triggered the given number of sync and sync-and-close events
     * 
     * @param appender
     * @param numSyncs
     * @param numRollovers
     */
    private void validateNumSyncAndCloseEvents(
            RollingFileTestAppender appender,
            int numSyncs,
            int numRollovers
    ) {
        long sleepTime = timeoutCheckPeriod * 2;
        // Sleep so the background threads have a chance to process any syncs and rollovers
        assertDoesNotThrow(() -> sleep(sleepTime));

        // Verify the expected num of syncs and rollovers
        long deadlineTimestamp = System.currentTimeMillis() + sleepTime;
        while (appender.getNumSyncs() != numSyncs) {
            if (System.currentTimeMillis() >= deadlineTimestamp) {
                assertEquals(numSyncs, appender.getNumSyncs());
            }
        }
        while (appender.getNumRollovers() != numRollovers) {
            if (System.currentTimeMillis() >= deadlineTimestamp) {
                assertEquals(numRollovers, appender.getNumRollovers());
            }
        }
    }

    /**
     * Creates and initializes a RollingFileTestAppender for the tests. Note that this method
     * doesn't call {@code activateOptions} on the appender.
     * 
     * @param disableSoftTimeout
     * @param disableHardTimeout
     * @return The created appender
     */
    private RollingFileTestAppender createTestAppender(
            boolean disableSoftTimeout,
            boolean disableHardTimeout
    ) {
        RollingFileTestAppender appender = new RollingFileTestAppender(patternLayout);

        // Set static settings
        appender._setOutputDir(outputDir);
        appender._setBaseName("test-file");
        appender._setCloseFrameOnFlush(true);
        appender._setTimeoutCheckPeriod(timeoutCheckPeriod);
        appender._setRolloverCompressedSizeThreshold(Integer.MAX_VALUE);
        appender._setRolloverUncompressedSizeThreshold(Integer.MAX_VALUE);
        appender._setCompressionLevel(3);
        appender._setShutdownSoftTimeout(5000);
        appender._setShutdownHardTimeout(30);
        appender._setCloseOnShutdown(true);

        // Set timeouts
        String disabledTimeoutCsv = "FATAL=" + Integer.MAX_VALUE + ",ERROR=" + Integer.MAX_VALUE
                + ", INFO=" + Integer.MAX_VALUE;
        String timeoutCsv = "FATAL=" + flushFatalLevelTimeout + ",ERROR=" + flushErrorLevelTimeout
                + ",INFO=" + flushInfoLevelTimeout;
        appender._setFlushHardTimeoutsInMinutes(
                disableHardTimeout ? disabledTimeoutCsv : timeoutCsv
        );
        appender._setFlushSoftTimeoutsInSeconds(
                disableSoftTimeout ? disabledTimeoutCsv : timeoutCsv
        );

        return appender;
    }
}
