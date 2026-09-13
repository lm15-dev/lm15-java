package dev.lm15.cloud;

import dev.lm15.errors.AuthError;
import dev.lm15.wire.Clock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Everything a cloud chain touches, injectable: the environment, the home
 * directory, an optional file map that replaces the filesystem (the harness
 * sandbox), the HTTP and subprocess seams ({@code null} = offline, the
 * doctor), the clock and the resolved host settings.
 */
public final class ChainContext {
    /** One local HTTP call: status, lowercased headers, body. */
    public record HttpResult(int status, Map<String, String> headers, byte[] body) {}

    @FunctionalInterface
    public interface HttpFn {
        HttpResult call(String method, String url, Map<String, String> headers, byte[] body, double timeoutSeconds);
    }

    @FunctionalInterface
    public interface RunFn {
        String run(List<String> argv, double timeoutSeconds);
    }

    private final Map<String, String> env;
    private final Path home;
    private final Map<String, String> files;
    private final HttpFn http;
    private final RunFn run;
    private final Clock clock;
    private Map<String, String> settings = Map.of();

    public ChainContext(Map<String, String> env, Path home, Map<String, String> files, HttpFn http, RunFn run, Clock clock) {
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.home = home != null ? home : defaultHome(this.env);
        this.files = files == null ? null : Map.copyOf(files);
        this.http = http;
        this.run = run;
        this.clock = clock == null ? Clock.SYSTEM : clock;
    }

    static Path defaultHome(Map<String, String> env) {
        String h = env.get("HOME");
        if (h != null && !h.isEmpty()) return Path.of(h);
        return Path.of(System.getProperty("user.home"));
    }

    /** Offline (the doctor): file and env rungs probed, network and subprocess rungs unprobed. */
    public static ChainContext offline(Map<String, String> env, Path home, Map<String, String> files) {
        return new ChainContext(env, home, files, null, null, Clock.SYSTEM);
    }

    /** Online (the router): real HTTP without redirects, real subprocesses under {@code env}. */
    public static ChainContext online(Map<String, String> env, Path home, Clock clock) {
        Map<String, String> values = env == null ? System.getenv() : env;
        return new ChainContext(values, home, null, ChainContext::defaultHttp, (argv, timeout) -> defaultRun(argv, timeout, values), clock);
    }

    /** Credential exchanges use the same injectable transport as inference, without doing IO here. */
    public static ChainContext online(Map<String, String> env, Path home, Clock clock, dev.lm15.transport.Transport transport) {
        ChainContext defaults = online(env, home, clock);
        HttpFn http = (method, url, headers, body, timeoutSeconds) -> {
            try {
                var request = new dev.lm15.wire.TransportRequest(method, url, List.of(), new ArrayList<>(headers.entrySet()),
                    null, body, Duration.ofMillis((long) Math.max(1, timeoutSeconds * 1000)));
                var response = transport.send(request);
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (var header : response.headers()) responseHeaders.put(header.getKey().toLowerCase(java.util.Locale.ROOT), header.getValue());
                return new HttpResult(response.status(), responseHeaders, response.body());
            } catch (RuntimeException e) {
                throw new AuthError("credential HTTP request failed");
            }
        };
        return new ChainContext(defaults.env(), defaults.home(), null, http, defaults.run(), defaults.clock());
    }

    public Map<String, String> env() { return env; }
    public Path home() { return home; }
    public Map<String, String> files() { return files; }
    public HttpFn http() { return http; }
    public RunFn run() { return run; }
    public Clock clock() { return clock; }
    public Map<String, String> settings() { return settings; }
    public ChainContext withSettings(Map<String, String> s) { this.settings = s == null ? Map.of() : Map.copyOf(s); return this; }
    public boolean offline() { return http == null; }

    public String env(String name) { return env.get(name); }

    /** True when the variable is set to a non-empty value. */
    public boolean has(String name) {
        String v = env.get(name);
        return v != null && !v.isEmpty();
    }

    public Path path(String text) {
        if (text.startsWith("~")) {
            String rest = text.substring(1);
            int i = 0;
            while (i < rest.length() && (rest.charAt(i) == '/' || rest.charAt(i) == '\\')) i++;
            return home.resolve(rest.substring(i));
        }
        return Path.of(text);
    }

    /** The file's text, from the file map when one is given, else the filesystem; null when unreadable. */
    public String read(String text) {
        if (files != null) {
            Path wanted = mappedPath(text);
            for (Map.Entry<String, String> f : files.entrySet()) {
                if (mappedPath(f.getKey()).equals(wanted)) return f.getValue();
            }
            return null;
        }
        try {
            return Files.readString(path(text), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Match a virtual file through existing directory aliases, including macOS
     * /var versus /private/var. The file itself need not exist on disk. */
    private Path mappedPath(String text) {
        Path absolute = path(text).toAbsolutePath();
        Path ancestor = absolute;
        var suffix = new java.util.ArrayDeque<String>();
        while (ancestor != null) {
            try {
                Path real = ancestor.toRealPath();
                for (String part : suffix) real = real.resolve(part);
                return real.normalize();
            } catch (IOException | SecurityException unavailable) {
                if (ancestor.getFileName() != null) suffix.addFirst(ancestor.getFileName().toString());
                ancestor = ancestor.getParent();
            }
        }
        return absolute.normalize();
    }

    public boolean exists(String text) { return read(text) != null; }

    /** Where {@code command} would run from, from the context's PATH only (an offline file check). */
    public String onPath(String command) {
        if (command.contains("/") || command.contains("\\")) return exists(command) ? command : null;
        String path = env.get("PATH");
        if (path == null || path.isEmpty()) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isEmpty()) continue;
            String candidate = stripTrailingSlash(directory) + "/" + command;
            if (files != null) {
                if (exists(candidate)) return candidate;
            } else {
                Path p = Path.of(candidate);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return candidate;
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    // ─── the default seams ───

    static HttpResult defaultHttp(String method, String url, Map<String, String> headers, byte[] body, double timeoutSeconds) {
        try {
            Duration timeout = Duration.ofMillis((long) Math.max(1, timeoutSeconds * 1000));
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getKey().equalsIgnoreCase("host") || h.getKey().equalsIgnoreCase("content-length")) continue;
                b.header(h.getKey(), h.getValue());
            }
            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            resp.headers().map().forEach((k, vs) -> { if (!vs.isEmpty()) out.put(k.toLowerCase(), vs.get(0)); });
            return new HttpResult(resp.statusCode(), out, resp.body());
        } catch (IOException | IllegalArgumentException e) {
            throw new AuthError("credential HTTP request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthError("credential HTTP request failed");
        }
    }

    static String defaultRun(List<String> argv, double timeoutSeconds, Map<String, String> env) {
        if (argv.isEmpty() || !Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0) throw new AuthError("invalid credential command");
        Process process = null;
        var readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<String> command = new ArrayList<>(argv);
            String executable = command.get(0);
            if (!executable.contains("/") && !executable.contains("\\")) {
                executable = ChainContext.offline(env, null, null).onPath(executable);
                if (executable == null) throw new AuthError("credential command not found in configured PATH");
                command.set(0, executable);
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().clear();
            pb.environment().putAll(env);
            process = pb.start();
            Process running = process;
            process.getOutputStream().close();
            long budget = (long) Math.min(Long.MAX_VALUE / 2.0, timeoutSeconds * 1_000_000_000);
            long deadline = System.nanoTime() + budget;
            var stdout = readers.submit(() -> commandOutput(running.getInputStream(), running));
            var stderr = readers.submit(() -> commandOutput(running.getErrorStream(), running));
            if (!process.waitFor(budget, TimeUnit.NANOSECONDS)) throw new AuthError("credential command timed out");
            byte[] out = stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) throw new AuthError("credential command exited " + process.exitValue());
            return new String(out, StandardCharsets.UTF_8);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AuthError("credential command timed out");
        } catch (IOException | java.util.concurrent.ExecutionException e) {
            throw new AuthError("credential command failed or exceeded its output limit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthError("credential command interrupted");
        } finally {
            if (process != null) {
                terminate(process);
                Process ended = process;
                // A helper can leave a descendant holding a pipe. Closing that pipe
                // must not hold the calling thread beyond its deadline.
                Thread.startVirtualThread(() -> {
                    for (var stream : List.of(ended.getInputStream(), ended.getErrorStream())) {
                        try { stream.close(); } catch (IOException ignored) { }
                    }
                });
            }
            readers.shutdownNow();
        }
    }

    private static byte[] commandOutput(java.io.InputStream stream, Process process) throws IOException {
        byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) {
            terminate(process);
            throw new IOException("credential command output exceeds limit");
        }
        return bytes;
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }
}
