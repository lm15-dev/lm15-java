package dev.lm15.cloud;

import dev.lm15.errors.AuthError;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandDeadlineTest {
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "sleep" -> Thread.sleep(10_000);
                case "noisy" -> { System.err.print("x".repeat(200_000)); System.out.print("credential-result"); }
                case "overflow" -> System.out.print("x".repeat(2 * 1024 * 1024));
                case "environment" -> System.out.print(System.getenv("ONLY") + ":" + System.getenv("HOME"));
                default -> throw new AssertionError("unknown fixture mode");
            }
        }
    }

    static List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            System.getProperty("java.class.path"), Fixture.class.getName(), mode);
    }

    @Test void timeoutIncludesReadingStdout() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            AuthError error = assertThrows(AuthError.class, () -> ChainContext.defaultRun(command("sleep"), 0.3, Map.of()));
            assertTrue(error.message().contains("timed out"));
        });
    }

    @Test void stderrIsDrainedConcurrentlyWithoutContaminatingStdout() {
        assertEquals("credential-result", ChainContext.defaultRun(command("noisy"), 5, Map.of()));
    }

    @Test void outputIsBounded() {
        assertThrows(AuthError.class, () -> ChainContext.defaultRun(command("overflow"), 5, Map.of()));
    }

    @Test void callerEnvironmentAndPathAreAuthoritative() {
        assertEquals("safe:null", ChainContext.defaultRun(command("environment"), 5, Map.of("ONLY", "safe")));
        assertThrows(AuthError.class, () -> ChainContext.defaultRun(List.of("java", "-version"), 1, Map.of("PATH", "")));
    }
}
