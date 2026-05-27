package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for what each plan allows. To add a feature to
 * a plan or change a quota, edit one entry below — call sites never
 * mention the plan name directly. To add a new {@link Feature} or
 * {@link Limit}, declare the enum value and add it here.
 *
 * <p>Limit value {@code -1} = unlimited.</p>
 */
public final class PlanConfig {

    public record Definition(Map<Limit, Integer> limits, Set<Feature> features) {}

    private static final Map<Plan, Definition> MATRIX;

    static {
        MATRIX = new EnumMap<>(Plan.class);

        MATRIX.put(Plan.FREE, new Definition(
                Map.of(Limit.MAX_PROJECTS, 2),
                EnumSet.noneOf(Feature.class)
        ));

        MATRIX.put(Plan.PRO, new Definition(
                Map.of(Limit.MAX_PROJECTS, -1),
                EnumSet.of(
                        Feature.BRANDED_PDF,
                        Feature.CLIENT_PORTAL,
                        Feature.ONLINE_SIGNATURE,
                        Feature.PHOTO_REPORTS
                )
        ));

        MATRIX.put(Plan.TEAM, new Definition(
                Map.of(Limit.MAX_PROJECTS, -1),
                EnumSet.of(
                        Feature.BRANDED_PDF,
                        Feature.CLIENT_PORTAL,
                        Feature.ONLINE_SIGNATURE,
                        Feature.PHOTO_REPORTS,
                        Feature.AI_ASSISTANT
                )
        ));
    }

    private PlanConfig() {}

    public static int limit(Plan plan, Limit limit) {
        Integer value = MATRIX.get(plan).limits().get(limit);
        return value == null ? -1 : value;
    }

    public static boolean has(Plan plan, Feature feature) {
        return MATRIX.get(plan).features().contains(feature);
    }

    public static Set<Feature> featuresOf(Plan plan) {
        return MATRIX.get(plan).features();
    }

    /**
     * Lowest plan in enum-declaration order that grants this feature.
     * Used to build upgrade-suggestion messages ("available on plan PRO").
     */
    public static Plan minimumPlanFor(Feature feature) {
        for (Plan plan : Plan.values()) {
            if (has(plan, feature)) {
                return plan;
            }
        }
        throw new IllegalStateException("No plan grants feature " + feature);
    }
}
