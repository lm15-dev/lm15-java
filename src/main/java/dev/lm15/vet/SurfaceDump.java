package dev.lm15.vet;

import dev.lm15.json.Json;
import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonBuilder;
import dev.lm15.json.JsonObject;
import dev.lm15.json.JsonString;
import dev.lm15.json.JsonValue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code surface_dump}: every public canonical record's fields and every
 * vocabulary enum's values, by reflection over {@code dev.lm15.types} —
 * never a hand-maintained list (PROTOCOL.md).
 */
final class SurfaceDump {
    private SurfaceDump() {}

    static JsonObject op(JsonObject msg) {
        TreeMap<String, JsonValue> types = new TreeMap<>();
        TreeMap<String, JsonValue> enums = new TreeMap<>();
        for (Class<?> cls : classesIn("dev.lm15.types")) {
            if (!Modifier.isPublic(cls.getModifiers())) continue;
            if (cls.isRecord()) {
                List<JsonValue> fields = new ArrayList<>();
                for (RecordComponent c : cls.getRecordComponents()) fields.add(new JsonString(snake(c.getName())));
                try {
                    Method type = cls.getMethod("type");
                    if (type.getParameterCount() == 0 && !hasComponent(cls, "type")) fields.add(new JsonString("type"));
                } catch (NoSuchMethodException ignored) {
                    // no discriminator
                }
                types.put(displayName(cls), Json.obj("fields", new JsonArray(fields)));
            } else if (cls.isEnum()) {
                List<JsonValue> values = new ArrayList<>();
                for (Object constant : cls.getEnumConstants()) values.add(new JsonString(constant.toString()));
                enums.put(cls.getSimpleName(), new JsonArray(values));
            }
        }
        JsonBuilder out = new JsonBuilder();
        out.put("types", new JsonObject(types));
        out.put("enums", new JsonObject(enums));
        out.put("providers", AuthOps.reflectProviders());
        return out.build();
    }

    private static boolean hasComponent(Class<?> cls, String name) {
        for (RecordComponent c : cls.getRecordComponents()) if (c.getName().equals(name)) return true;
        return false;
    }

    /** Nested live-event records report as the family's flat names (LiveClientTurnEvent, …). */
    private static String displayName(Class<?> cls) {
        Class<?> outer = cls.getEnclosingClass();
        if (outer == null) return cls.getSimpleName();
        String inner = cls.getSimpleName();
        if (outer.getSimpleName().equals("LiveClientEvent")) return "LiveClient" + inner + "Event";
        if (outer.getSimpleName().equals("LiveServerEvent")) {
            return "LiveServer" + (inner.equals("UsageEvent") ? "Usage" : inner) + "Event";
        }
        return outer.getSimpleName() + "." + inner;
    }

    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static List<Class<?>> classesIn(String packageName) {
        TreeSet<String> names = new TreeSet<>();
        try {
            ClassLoader loader = SurfaceDump.class.getClassLoader();
            Enumeration<URL> urls = loader.getResources(packageName.replace('.', '/'));
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url.getProtocol().equals("file")) {
                    java.io.File dir = new java.io.File(url.toURI());
                    java.io.File[] files = dir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            if (f.getName().endsWith(".class")) names.add(f.getName().substring(0, f.getName().length() - 6));
                        }
                    }
                } else if (url.getProtocol().equals("jar")) {
                    String path = url.getPath().substring(5, url.getPath().indexOf('!'));
                    try (JarFile jar = new JarFile(java.net.URLDecoder.decode(path, "UTF-8"))) {
                        Enumeration<JarEntry> entries = jar.entries();
                        String prefix = packageName.replace('.', '/') + "/";
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(prefix) && name.endsWith(".class") && name.indexOf('/', prefix.length()) < 0) {
                                names.add(name.substring(prefix.length(), name.length() - 6));
                            }
                        }
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<Class<?>> out = new ArrayList<>();
        for (String n : names) {
            try {
                out.add(Class.forName(packageName + "." + n));
            } catch (ClassNotFoundException ignored) {
                // not loadable: skip
            }
        }
        return out;
    }
}
