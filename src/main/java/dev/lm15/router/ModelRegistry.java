package dev.lm15.router;

import dev.lm15.json.JsonArray;
import dev.lm15.json.JsonValue;
import dev.lm15.serde.Canonical;
import dev.lm15.types.ModelInfo;
import dev.lm15.types.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * An explicit model catalog (lm15-python {@code lm15.models.ModelRegistry}):
 * canonical {@link ModelInfo} values keyed by (provider, id), with their
 * aliases. Insertion order is catalog order — the order
 * {@code AmbiguousModelError.providers} reports. There is no entry-point
 * discovery in this port (a stated NEVER for non-Python ports): a catalog is
 * built from values the caller holds, or from canonical {@code model_info}
 * JSON.
 */
public final class ModelRegistry {
    private final Map<Key, ModelInfo> models = new LinkedHashMap<>();
    private final Map<Key, Key> aliases = new LinkedHashMap<>();

    private record Key(String provider, String model) {}

    public ModelRegistry() {}

    /** A registry over the given values, in order. */
    public static ModelRegistry of(List<ModelInfo> infos) {
        ModelRegistry r = new ModelRegistry();
        for (ModelInfo m : infos) r.add(m);
        return r;
    }

    /** A registry from canonical {@code model_info} JSON objects (serde-validated). */
    public static ModelRegistry fromJson(JsonArray dicts) {
        ModelRegistry r = new ModelRegistry();
        for (JsonValue v : dicts) r.add(Canonical.modelInfoFromJson(v.asObject()));
        return r;
    }

    public void add(ModelInfo model) { add(model, true); }

    public void add(ModelInfo model, boolean replace) {
        if (model == null) throw ValidationException.type("model must be a ModelInfo");
        Key key = new Key(model.provider(), model.id());
        if (!replace && models.containsKey(key)) {
            throw ValidationException.value("model already registered: " + model.provider() + "/" + model.id());
        }
        models.put(key, model);
        for (String alias : model.aliases()) aliases.put(new Key(model.provider(), alias), key);
    }

    /** The entry for (provider, id-or-alias), or null. */
    public ModelInfo get(String provider, String model) {
        Key key = new Key(provider, model);
        ModelInfo direct = models.get(key);
        if (direct != null) return direct;
        Key target = aliases.get(key);
        return target == null ? null : models.get(target);
    }

    /** The single entry whose id or alias is {@code model} (under {@code provider} when given), or null when none or several. */
    public ModelInfo resolve(String model, String provider) {
        if (provider != null) return get(provider, model);
        List<ModelInfo> matches = new ArrayList<>();
        for (ModelInfo info : models.values()) {
            if (info.id().equals(model) || info.aliases().contains(model)) matches.add(info);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public ModelInfo resolve(String model) { return resolve(model, null); }

    /** Every entry, catalog order. */
    public List<ModelInfo> list() { return List.copyOf(models.values()); }

    /** The entries of one provider, catalog order. */
    public List<ModelInfo> list(String provider) {
        if (provider == null) return list();
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo info : models.values()) if (info.provider().equals(provider)) out.add(info);
        return List.copyOf(out);
    }

    /** The providers with at least one entry, sorted. */
    public List<String> providers() {
        TreeSet<String> out = new TreeSet<>();
        for (Key k : models.keySet()) out.add(k.provider());
        return List.copyOf(out);
    }

    public int size() { return models.size(); }
    public boolean isEmpty() { return models.isEmpty(); }

    @Override public String toString() { return "ModelRegistry(" + models.size() + " models)"; }
}
