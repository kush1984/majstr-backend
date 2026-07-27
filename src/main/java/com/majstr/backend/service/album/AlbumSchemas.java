package com.majstr.backend.service.album;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON schemas for {@code output_config.format} — one per extraction stage, mirroring
 * {@code extraction-schema.json}. Structured-outputs rules baked in: every object has
 * {@code additionalProperties:false} and lists ALL properties in {@code required};
 * numeric bounds are unsupported, so range sanity lives in Java ({@code ElectroTakeoffCalc}
 * and the orchestrating service), and nullability is {@code anyOf [T, null]}.
 */
final class AlbumSchemas {

    private AlbumSchemas() {}

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> INT = Map.of("type", "integer");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    private static final Map<String, Object> BOOL = Map.of("type", "boolean");
    private static final Map<String, Object> NULL = Map.of("type", "null");

    private static Map<String, Object> nullable(Map<String, Object> type) {
        return Map.of("anyOf", List.of(type, NULL));
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    /** Object with additionalProperties:false and every property required. */
    private static Map<String, Object> obj(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("properties", properties);
        return schema;
    }

    private static final Map<String, Object> STATUS =
            enumOf("from_spec", "counted", "assumed", "missing");

    private static final Map<String, Object> POINT_TYPE = enumOf(
            "socket", "socket_wet", "socket_tv", "socket_net", "socket_cable_channel",
            "socket_usb", "power_outlet_220", "cable_lead_220", "doorphone_outlet",
            "switch_1key", "switch_2key", "switch_pass_1key", "switch_pass_2key",
            "switch_cross", "switch_vent", "switch_master",
            "led_output", "led_transformer", "thermostat", "motion_sensor", "router", "other");

    // ---- entity schemas --------------------------------------------------------

    private static Map<String, Object> metaSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("project_name", nullable(STRING));
        p.put("address", nullable(STRING));
        p.put("floors", INT);
        p.put("total_area_m2", nullable(NUMBER));
        p.put("input_kind", enumOf("multi_page_pdf", "sheet_files", "photos", "mixed"));
        p.put("is_design_album", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> sheetSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", INT);
        p.put("source_file", nullable(STRING));
        p.put("title", STRING);
        p.put("kind", enumOf("title", "sheet_list", "measurement_plan",
                "measurement_plan_after_remodel", "demolition", "construction",
                "room_explication", "doors", "windows", "plumbing", "floors_finish",
                "floor_heating", "ceilings", "wall_finish", "ventilation_ac",
                "sockets_switches", "light_fixtures", "light_switching", "light_groups",
                "furniture", "elevations", "detail", "specification", "other", "unreadable"));
        p.put("floor", nullable(INT));
        p.put("readable", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> dataAvailabilitySchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("data_kind", enumOf("room_areas", "ceiling_heights", "opening_sizes",
                "window_heights", "door_spec", "electrical_point_counts", "light_groups",
                "fixture_spec", "floor_heating_type", "panel_location", "single_line_diagram"));
        p.put("status", enumOf("available", "manual_count_needed", "partial", "missing"));
        p.put("source_sheets", arrayOf(INT));
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> roomSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("floor", INT);
        p.put("number", nullable(STRING));
        p.put("name", STRING);
        p.put("dims_mm", STRING);
        p.put("perimeter_m", nullable(NUMBER));
        p.put("area_calc_m2", nullable(NUMBER));
        p.put("area_spec_m2", nullable(NUMBER));
        p.put("ceiling_h_mm", nullable(INT));
        p.put("ceiling_note", nullable(STRING));
        p.put("status", STATUS);
        p.put("verify", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> openingSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("floor", INT);
        p.put("room_a", STRING);
        p.put("room_b", nullable(STRING));
        p.put("kind", enumOf("door_interior", "door_entrance", "door_exterior", "door_sliding",
                "window", "window_panoramic", "window_roof", "opening", "stairs_opening",
                "partition_sliding"));
        p.put("width_mm", nullable(INT));
        p.put("height_mm", nullable(INT));
        p.put("sill_mm", nullable(INT));
        p.put("to_floor", BOOL);
        p.put("mark", nullable(STRING));
        p.put("status", STATUS);
        p.put("verify", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> electricalPointSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("floor", INT);
        p.put("room", STRING);
        p.put("point_type", POINT_TYPE);
        p.put("qty", INT);
        p.put("height_mm", nullable(INT));
        p.put("purpose", nullable(STRING));
        p.put("status", STATUS);
        p.put("verify", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> lightingSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("floor", INT);
        p.put("room", STRING);
        p.put("fixture_kind", enumOf("pendant", "chandelier", "recessed", "recessed_double",
                "surface_spot", "wall_sconce", "track", "led_strip", "led_profile", "outdoor",
                "exhaust_fan", "power_lead_out", "other"));
        p.put("position_mark", nullable(STRING));
        p.put("qty", INT);
        p.put("height_mm", nullable(INT));
        p.put("length_mm", nullable(INT));
        p.put("status", STATUS);
        p.put("verify", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> lightGroupSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("group_id", STRING);
        p.put("floor", INT);
        p.put("controls", STRING);
        p.put("switch_description", STRING);
        p.put("control_points", INT);
        p.put("status", STATUS);
        p.put("verify", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> floorHeatingSchema() {
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("floor", INT);
        zone.put("area_m2", nullable(NUMBER));
        zone.put("rooms", arrayOf(STRING));
        zone.put("status", STATUS);
        zone.put("verify", BOOL);
        zone.put("note", nullable(STRING));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("present", BOOL);
        p.put("system_type", enumOf("electric", "water", "unknown"));
        p.put("zones", arrayOf(obj(zone)));
        p.put("thermostats_shown", BOOL);
        p.put("note", nullable(STRING));
        return obj(p);
    }

    private static Map<String, Object> panelLocationSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("known", BOOL);
        p.put("description", nullable(STRING));
        return obj(p);
    }

    // ---- stage schemas -----------------------------------------------------------

    static final Map<String, Object> INVENTORY = stage(Map.of(
            "meta", metaSchema(),
            "sheets", arrayOf(sheetSchema()),
            "data_availability", arrayOf(dataAvailabilitySchema())));

    static final Map<String, Object> ROOMS_AND_OPENINGS = stageWithHonesty(Map.of(
            "rooms", arrayOf(roomSchema()),
            "openings", arrayOf(openingSchema())));

    static final Map<String, Object> POINTS = stageWithHonesty(Map.of(
            "electrical_points", arrayOf(electricalPointSchema())));

    static final Map<String, Object> LIGHTING = stageWithHonesty(Map.of(
            "lighting", arrayOf(lightingSchema()),
            "light_groups", arrayOf(lightGroupSchema())));

    static final Map<String, Object> HEATING = stageWithHonesty(Map.of(
            "floor_heating", floorHeatingSchema(),
            "panel_location", panelLocationSchema(),
            "electrical_points", arrayOf(electricalPointSchema())));

    private static Map<String, Object> stage(Map<String, Object> properties) {
        return obj(new LinkedHashMap<>(properties));
    }

    /** Every extraction stage also returns its honesty lists. */
    private static Map<String, Object> stageWithHonesty(Map<String, Object> properties) {
        Map<String, Object> p = new LinkedHashMap<>(properties);
        p.put("uncertain", arrayOf(STRING));
        p.put("missing", arrayOf(STRING));
        return obj(p);
    }
}
