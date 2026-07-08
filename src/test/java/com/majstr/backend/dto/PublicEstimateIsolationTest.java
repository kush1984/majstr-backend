package com.majstr.backend.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the object-economy isolation rule: the public estimate view
 * served over a share token must NEVER carry object expenses or profit. Reflects the
 * whole record tree so a future accidental field (e.g. an added `economy`) fails here
 * instead of leaking to the client portal / PDF.
 */
class PublicEstimateIsolationTest {

    private static final String[] FORBIDDEN = {"expense", "profit", "economy", "cost", "margin"};

    @Test
    void publicEstimateViewCarriesNoEconomyData() {
        List<String> names = new ArrayList<>();
        collect(PublicEstimateView.class, names, 0);
        assertThat(names).isNotEmpty();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertThat(lower)
                        .as("public share DTO must not expose '%s' (found component '%s')", bad, name)
                        .doesNotContain(bad);
            }
        }
    }

    private static void collect(Class<?> type, List<String> names, int depth) {
        if (type == null || !type.isRecord() || depth > 5) {
            return;
        }
        for (RecordComponent rc : type.getRecordComponents()) {
            names.add(rc.getName());
            collect(rc.getType(), names, depth + 1); // nested record
            // element type of List<...> components
            if (rc.getGenericType() instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> c) {
                        collect(c, names, depth + 1);
                    }
                }
            }
        }
    }
}
