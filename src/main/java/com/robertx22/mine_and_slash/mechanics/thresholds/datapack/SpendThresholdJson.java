package com.robertx22.mine_and_slash.mechanics.thresholds.datapack;

import com.google.gson.annotations.SerializedName;
import com.robertx22.library_of_exile.registry.ExileRegistryType;
import com.robertx22.library_of_exile.registry.IAutoGson;
import com.robertx22.library_of_exile.registry.JsonExileRegistry;
import com.robertx22.mine_and_slash.database.registry.ExileRegistryTypes;
import com.robertx22.mine_and_slash.mechanics.thresholds.DataDrivenSpendThresholdSpec;
import com.robertx22.mine_and_slash.mechanics.thresholds.SpendThresholdSpec;
import com.robertx22.mine_and_slash.saveclasses.unit.ResourceType;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Datapack-backed registry entry for spend thresholds.
 */
public class SpendThresholdJson implements JsonExileRegistry<SpendThresholdJson>, IAutoGson<SpendThresholdJson> {

    public static SpendThresholdJson SERIALIZER = new SpendThresholdJson();

    public String key;
    public String resource = "";
    public boolean enabled = true;
    public int priority = 0;
    @SerializedName("show_ui")
    public boolean showUi = false;

    public static class Threshold {
        public String mode = "FLAT";
        public float value = 0f;
        @SerializedName("multiply_by_level") public boolean multiplyByLevel = false;
        @SerializedName("percent_of") public String percentOf; // optional
    }
    public Threshold threshold = new Threshold();

    public static class Locks {
        public List<String> effects = new ArrayList<>();
        @SerializedName("lock_while_cooldown") public boolean lockWhileCooldown = false;
        @SerializedName("drop_progress_while_locked") public boolean dropProgressWhileLocked = true;
        @SerializedName("reset_progress_on_proc") public boolean resetProgressOnProc = true;
    }
    public Locks locks = new Locks();

    @SerializedName("cooldown_ticks")
    public int cooldownTicks = 0;

    @SerializedName("require_stat")
    public String requireStatId = "";

    public static class ProcAction {
        public String action; // "exile_effect"
        @SerializedName("exile_potion_id") public String effectId;
        @SerializedName("duration_ticks") public int durationTicks = 0;
        public int stacks = 1;
        @SerializedName("on_expire") public java.util.Map<String, Integer> onExpire = java.util.Collections.emptyMap();
    }
    @SerializedName("on_proc")
    public List<ProcAction> onProc = new ArrayList<>();

    public SpendThresholdSpec toSpec() {
        ResourceType res = parseResource(resource, ResourceType.energy);
        String normKey = sanitizeId(key);

        String rawMode = (threshold.mode == null ? "FLAT" : threshold.mode.trim()).toUpperCase(Locale.ROOT);
        boolean mult = threshold.multiplyByLevel;
        DataDrivenSpendThresholdSpec.ThresholdMode mode;
        if ("PERCENT_OF_MAX".equals(rawMode)) {
            mode = DataDrivenSpendThresholdSpec.ThresholdMode.PCT_OF_MAX;
        } else if ("X_PER_LEVEL".equals(rawMode)) {
            mode = DataDrivenSpendThresholdSpec.ThresholdMode.X_PER_LEVEL;
        } else {
            mode = DataDrivenSpendThresholdSpec.ThresholdMode.FLAT;
        }

        ResourceType percentOf = null;
        if (mode == DataDrivenSpendThresholdSpec.ThresholdMode.PCT_OF_MAX
                && threshold.percentOf != null && !threshold.percentOf.isEmpty()) {
            percentOf = parseResource(threshold.percentOf, res);
        }

        Set<String> lockEff = (locks != null && locks.effects != null)
                ? new HashSet<>(locks.effects) : Collections.emptySet();

        return new DataDrivenSpendThresholdSpec(
                normKey,
                res,
                mode,
                threshold.value,
                mult,
                percentOf,
                lockEff,
                cooldownTicks,
                locks != null && locks.lockWhileCooldown,
                locks != null && locks.dropProgressWhileLocked,
                locks != null && locks.resetProgressOnProc
        ) {
            @Override
            public void onProc(ServerPlayer sp, int procs) {
                if (onProc == null || onProc.isEmpty()) return;
                for (ProcAction a : onProc) {
                    if (!"exile_effect".equalsIgnoreCase(a.action) || a.effectId == null) continue;
                    var effect = com.robertx22.mine_and_slash.database.registry.ExileDB.ExileEffects().get(a.effectId);
                    if (effect == null) continue;

                    int durTicks = Math.max(1, a.durationTicks);
                    int stacks = Math.max(1, a.stacks);
                    var inst = com.robertx22.mine_and_slash.event_hooks.my_events.EffectUtils.applyEffect(sp, effect, durTicks, stacks);

                    if (a.onExpire != null && !a.onExpire.isEmpty()) {
                        if (inst.onExpireEffectDurationTicks == null) {
                            inst.onExpireEffectDurationTicks = new java.util.HashMap<>();
                        }
                        for (var e : a.onExpire.entrySet()) {
                            int ticks = Math.max(0, e.getValue());
                            if (ticks > 0) {
                                inst.onExpireEffectDurationTicks.put(e.getKey(), ticks);
                            }
                        }
                    }
                }
            }

            @Override
            public boolean isLockedFor(com.robertx22.mine_and_slash.capability.entity.EntityData unit) {
                if (super.isEffectLocked(unit)) return true;
                if (requireStatId != null && !requireStatId.isEmpty()) {
                    var st = com.robertx22.mine_and_slash.database.registry.ExileDB.Stats().get(requireStatId);
                    if (st != null) {
                        return unit.getUnit().getCalculatedStat(st).getValue() <= 0;
                    }
                }
                return false;
            }
        }.withPriority(priority).withShowUi(showUi);
    }

    private static ResourceType parseResource(String s, ResourceType fallback) {
        if (s == null) return fallback;
        for (ResourceType rt : ResourceType.values()) {
            if (rt.name().equalsIgnoreCase(s)) return rt;
            try {
                var idField = rt.getClass().getField("id");
                Object idVal = idField.get(rt);
                if (idVal instanceof String && ((String) idVal).equalsIgnoreCase(s)) return rt;
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
        }
        return fallback;
    }

    @Override
    public ExileRegistryType getExileRegistryType() {
        return ExileRegistryTypes.SPEND_THRESHOLD;
    }

    @Override
    public String GUID() {
        return sanitizeId(key);
    }

    private static String sanitizeId(String id) {
        String s = (id == null ? "" : id).toLowerCase(java.util.Locale.ROOT);
        // allow only a-z 0-9 _ . - ; replace others with underscore
        return s.replaceAll("[^a-z0-9._-]", "_");
    }

    @Override
    public Class<SpendThresholdJson> getClassForSerialization() {
        return SpendThresholdJson.class;
    }

    @Override
    public int Weight() {
        return 1000;
    }
}


