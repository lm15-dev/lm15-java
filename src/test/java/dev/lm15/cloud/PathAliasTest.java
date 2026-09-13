package dev.lm15.cloud;

import dev.lm15.auth.Doctor;
import dev.lm15.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathAliasTest {
    @Test void mappedFilesAndExecutablesMatchThroughSymlinkedHomes(@TempDir Path directory) throws Exception {
        Path real = Files.createDirectory(directory.resolve("real-home")).toRealPath();
        Path alias = directory.resolve("home-alias");
        Files.createSymbolicLink(alias, real);
        // The virtual files need not exist: only their parent directory has an alias.
        Map<String, String> files = Map.of(
            real.resolve(".aws/credentials").toString(), "[default]\naws_access_key_id=AKIDEXAMPLE\naws_secret_access_key=example-secret\n",
            real.resolve("bin/az").toString(), "virtual executable");
        Map<String, String> env = Map.of("HOME", alias.toString(), "PATH", alias.resolve("bin").toString(),
            "AWS_EC2_METADATA_DISABLED", "true");
        ChainContext context = ChainContext.offline(env, alias, files);
        assertNotNull(context.read("~/.aws/credentials"));
        assertNotNull(context.onPath("az"));
        var report = Doctor.explainAuth("aws-anthropic", Map.of(), env, null, files, alias.toString(), Map.of("region", "us-east-1", "workspace", "test"));
        assertTrue(report.configured());
        assertTrue(report.steps().stream().anyMatch(step -> step.kind().equals("shared-credentials-file") && step.state().equals("selected")));
    }

    @Test void adcMapUsesSameFileIdentityAsItsAbsoluteEnvironmentPath(@TempDir Path directory) throws Exception {
        Path real = Files.createDirectory(directory.resolve("real-home")).toRealPath();
        Path alias = directory.resolve("home-alias");
        Files.createSymbolicLink(alias, real);
        String file = "config/adc.json";
        String json = Json.write(Json.obj("type", "authorized_user", "client_id", "client", "client_secret", "secret",
            "refresh_token", "refresh", "quota_project_id", "project"));
        Map<String, String> env = Map.of("HOME", alias.toString(), "GOOGLE_APPLICATION_CREDENTIALS", alias.resolve(file).toString(), "NO_GCE_CHECK", "true");
        var report = Doctor.explainAuth("vertex", Map.of(), env, null, Map.of(real.resolve(file).toString(), json), alias.toString(), Map.of("location", "us-central1"));
        assertTrue(report.configured());
        assertEquals("project", report.settings().stream().filter(entry -> entry.getKey().equals("project")).findFirst().orElseThrow().getValue());
    }
}
