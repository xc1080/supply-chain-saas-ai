package com.ruoyi;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

/** Exercise Boot's real early logging initialization without business DBs. */
public class LoggingStartupTest {
    @Configuration
    public static class ProbeApplication { }

    @Test public void defaultLogsAreWritableUnderUserHome() throws Exception {
        verifyLogging(false, false);
    }

    @Test public void environmentCanOverrideLogDirectory() throws Exception {
        verifyLogging(true, false);
    }

    @Test public void commandLineCanOverrideLogDirectory() throws Exception {
        verifyLogging(false, true);
    }

    private void verifyLogging(boolean environmentOverride, boolean commandOverride) throws Exception {
        Path temporary = Files.createTempDirectory("logging-startup-");
        Path home = temporary.resolve("home");
        Files.createDirectory(home);
        Path expected = environmentOverride || commandOverride ? temporary.resolve("custom logs")
                : home.resolve(".supply-chain-saas/logs");
        String previousHome = System.getProperty("user.home");
        LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        ConfigurableApplicationContext context = null;
        try {
            System.setProperty("user.home", home.toString());
            logging.reset();
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            if (environmentOverride) {
                environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-environment",
                        Collections.<String, Object>singletonMap("LOGGING_FILE_PATH", expected.toString())));
            }
            SpringApplication application = new SpringApplication(ProbeApplication.class);
            application.setEnvironment(environment);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setRegisterShutdownHook(false);
            application.setDefaultProperties(Collections.<String, Object>singletonMap("spring.config.location",
                    "optional:classpath:logging-startup-empty.properties"));
            context = commandOverride ? application.run("--logging.file.path=" + expected) : application.run();
            assertTrue(context.isActive());
            LoggerFactory.getLogger("com.ruoyi.logging-probe").info("startup-info-proof");
            LoggerFactory.getLogger("com.ruoyi.logging-probe").error("startup-error-proof");
            LoggerFactory.getLogger("sys-user").info("startup-user-proof");
            for (Status status : logging.getStatusManager().getCopyOfStatusList()) {
                assertTrue(status.toString(), status.getLevel() < Status.ERROR);
            }
            logging.stop();
            assertContains(expected.resolve("sys-info.log"), "startup-info-proof");
            assertContains(expected.resolve("sys-error.log"), "startup-error-proof");
            assertContains(expected.resolve("sys-user.log"), "startup-user-proof");
        } finally {
            if (context != null) context.close();
            logging.stop();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void assertContains(Path file, String message) throws Exception {
        assertTrue("Missing configured log file: " + file, Files.isRegularFile(file));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(message));
    }
}
