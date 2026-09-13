package dev.lm15.auth;

import java.util.List;

/** One host setting: its name, the env variables consulted in order, and its default (null = required). */
public record HostSetting(String name, List<String> env, String defaultValue) {
    public HostSetting {
        env = env == null ? List.of() : List.copyOf(env);
    }

    public HostSetting(String name, List<String> env) { this(name, env, null); }
}
