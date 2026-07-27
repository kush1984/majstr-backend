package com.majstr.backend.service.album;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result of the design-album recognition (the API contract with Claude) — the Java
 * mirror of {@code extraction-schema.json}. Field names follow the wire snake_case
 * via {@link JsonProperty}; every leaf carries a {@link Status} and a {@code verify}
 * flag so the review screen can colour-code what the master must double-check.
 *
 * <p>Honesty model: {@code FROM_SPEC} = taken from a designer's specification table
 * (authoritative), {@code COUNTED} = counted from plan symbols (needs review),
 * {@code ASSUMED} = an assumption (e.g. ceiling height "same as next room"),
 * {@code MISSING} = absent from the album — never invented.</p>
 */
public record AlbumExtraction(
        Meta meta,
        List<Sheet> sheets,
        @JsonProperty("data_availability") List<DataAvailability> dataAvailability,
        List<Room> rooms,
        List<Opening> openings,
        @JsonProperty("electrical_points") List<ElectricalPoint> electricalPoints,
        List<Lighting> lighting,
        @JsonProperty("light_groups") List<LightGroup> lightGroups,
        @JsonProperty("floor_heating") FloorHeating floorHeating,
        @JsonProperty("panel_location") PanelLocation panelLocation,
        List<String> uncertain,
        List<String> missing
) {

    public enum Status {
        @JsonProperty("from_spec") FROM_SPEC,
        @JsonProperty("counted") COUNTED,
        @JsonProperty("assumed") ASSUMED,
        @JsonProperty("missing") MISSING
    }

    /** Electrical point types — mirrors the schema enum; helpers drive the calc branching. */
    public enum PointType {
        @JsonProperty("socket") SOCKET,
        @JsonProperty("socket_wet") SOCKET_WET,
        @JsonProperty("socket_tv") SOCKET_TV,
        @JsonProperty("socket_net") SOCKET_NET,
        @JsonProperty("socket_cable_channel") SOCKET_CABLE_CHANNEL,
        @JsonProperty("socket_usb") SOCKET_USB,
        @JsonProperty("power_outlet_220") POWER_OUTLET_220,
        @JsonProperty("cable_lead_220") CABLE_LEAD_220,
        @JsonProperty("doorphone_outlet") DOORPHONE_OUTLET,
        @JsonProperty("switch_1key") SWITCH_1KEY,
        @JsonProperty("switch_2key") SWITCH_2KEY,
        @JsonProperty("switch_pass_1key") SWITCH_PASS_1KEY,
        @JsonProperty("switch_pass_2key") SWITCH_PASS_2KEY,
        @JsonProperty("switch_cross") SWITCH_CROSS,
        @JsonProperty("switch_vent") SWITCH_VENT,
        @JsonProperty("switch_master") SWITCH_MASTER,
        @JsonProperty("led_output") LED_OUTPUT,
        @JsonProperty("led_transformer") LED_TRANSFORMER,
        @JsonProperty("thermostat") THERMOSTAT,
        @JsonProperty("motion_sensor") MOTION_SENSOR,
        @JsonProperty("router") ROUTER,
        @JsonProperty("other") OTHER;

        /** Ordinary socket points fed by shared 3×2.5 lines. */
        public boolean isGeneralSocket() {
            return this == SOCKET || this == SOCKET_WET || this == SOCKET_CABLE_CHANNEL
                    || this == SOCKET_USB;
        }

        /** Appliance feeds that get a dedicated line from the panel. */
        public boolean isDedicatedLead() {
            return this == POWER_OUTLET_220 || this == CABLE_LEAD_220 || this == DOORPHONE_OUTLET;
        }

        /** Low-voltage points (UTP/coax) — not part of the power-cable totals. */
        public boolean isLowVoltage() {
            return this == SOCKET_TV || this == SOCKET_NET || this == ROUTER;
        }

        public boolean isSwitch() {
            return this == SWITCH_1KEY || this == SWITCH_2KEY || this == SWITCH_PASS_1KEY
                    || this == SWITCH_PASS_2KEY || this == SWITCH_CROSS || this == SWITCH_VENT
                    || this == SWITCH_MASTER;
        }

        /** Points that live on the lighting circuits (3×1.5). */
        public boolean isLightingPoint() {
            return this == LED_OUTPUT || this == LED_TRANSFORMER || this == MOTION_SENSOR;
        }
    }

    public record Meta(
            @JsonProperty("project_name") String projectName,
            String address,
            Integer floors,
            @JsonProperty("total_area_m2") Double totalAreaM2,
            @JsonProperty("input_kind") String inputKind,
            @JsonProperty("is_design_album") boolean isDesignAlbum,
            String note
    ) {}

    public record Sheet(
            int index,
            @JsonProperty("source_file") String sourceFile,
            String title,
            String kind,
            Integer floor,
            boolean readable,
            String note
    ) {}

    public record DataAvailability(
            @JsonProperty("data_kind") String dataKind,
            String status,
            @JsonProperty("source_sheets") List<Integer> sourceSheets,
            String note
    ) {}

    public record Room(
            int floor,
            String number,
            String name,
            @JsonProperty("dims_mm") String dimsMm,
            @JsonProperty("perimeter_m") Double perimeterM,
            @JsonProperty("area_calc_m2") Double areaCalcM2,
            @JsonProperty("area_spec_m2") Double areaSpecM2,
            @JsonProperty("ceiling_h_mm") Integer ceilingHMm,
            @JsonProperty("ceiling_note") String ceilingNote,
            Status status,
            boolean verify,
            String note
    ) {}

    public record Opening(
            int floor,
            @JsonProperty("room_a") String roomA,
            @JsonProperty("room_b") String roomB,
            String kind,
            @JsonProperty("width_mm") Integer widthMm,
            @JsonProperty("height_mm") Integer heightMm,
            @JsonProperty("sill_mm") Integer sillMm,
            @JsonProperty("to_floor") boolean toFloor,
            String mark,
            Status status,
            boolean verify,
            String note
    ) {}

    /** One entry = a block of same-type points at one spot (e.g. "4 sockets h=300 by the bed"). */
    public record ElectricalPoint(
            int floor,
            String room,
            @JsonProperty("point_type") PointType pointType,
            int qty,
            @JsonProperty("height_mm") Integer heightMm,
            String purpose,
            Status status,
            boolean verify,
            String note
    ) {}

    public record Lighting(
            int floor,
            String room,
            @JsonProperty("fixture_kind") String fixtureKind,
            @JsonProperty("position_mark") String positionMark,
            int qty,
            @JsonProperty("height_mm") Integer heightMm,
            @JsonProperty("length_mm") Integer lengthMm,
            Status status,
            boolean verify,
            String note
    ) {}

    public record LightGroup(
            @JsonProperty("group_id") String groupId,
            int floor,
            String controls,
            @JsonProperty("switch_description") String switchDescription,
            @JsonProperty("control_points") int controlPoints,
            Status status,
            boolean verify,
            String note
    ) {}

    public record FloorHeating(
            boolean present,
            @JsonProperty("system_type") SystemType systemType,
            List<Zone> zones,
            @JsonProperty("thermostats_shown") boolean thermostatsShown,
            String note
    ) {
        public enum SystemType {
            @JsonProperty("electric") ELECTRIC,
            @JsonProperty("water") WATER,
            @JsonProperty("unknown") UNKNOWN
        }

        /** {@code verify} is a wrapper (nullable) — added in contract v1.1, so fixtures
         *  produced under v1.0 deserialize with {@code null} = "not flagged". */
        public record Zone(int floor, @JsonProperty("area_m2") Double areaM2,
                           List<String> rooms, Status status, Boolean verify, String note) {}
    }

    public record PanelLocation(boolean known, String description) {}

    // ---- per-stage partial results (what each Claude call returns) ------------

    /** Stage 1 — sheet inventory + data-availability matrix. */
    public record Inventory(
            Meta meta,
            List<Sheet> sheets,
            @JsonProperty("data_availability") List<DataAvailability> dataAvailability
    ) {}

    /** Stage 2A — rooms and openings. */
    public record RoomsAndOpenings(
            List<Room> rooms,
            List<Opening> openings,
            List<String> uncertain,
            List<String> missing
    ) {}

    /** Stage 2B — electrical points of one floor. */
    public record PointsResult(
            @JsonProperty("electrical_points") List<ElectricalPoint> electricalPoints,
            List<String> uncertain,
            List<String> missing
    ) {}

    /** Stage 2C — light fixtures + switching groups. */
    public record LightingResult(
            List<Lighting> lighting,
            @JsonProperty("light_groups") List<LightGroup> lightGroups,
            List<String> uncertain,
            List<String> missing
    ) {}

    /** Stage 2D — floor heating, AC/vent power points, panel location. */
    public record HeatingResult(
            @JsonProperty("floor_heating") FloorHeating floorHeating,
            @JsonProperty("panel_location") PanelLocation panelLocation,
            @JsonProperty("electrical_points") List<ElectricalPoint> electricalPoints,
            List<String> uncertain,
            List<String> missing
    ) {}
}
