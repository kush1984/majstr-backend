package com.majstr.backend.service.album;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generalization harness: feeds REAL-ALBUM extraction fixtures (JSON conforming to
 * extraction-schema.json, produced by running the recognition methodology over public
 * design projects) through both deterministic calculators. Guards the wire contract
 * (every fixture must deserialize into {@link AlbumExtraction}) and the calculators'
 * robustness on real-world shapes (no exceptions, sane non-negative outputs, honesty
 * lists surfaced). Prints a per-fixture summary for eyeballing.
 *
 * <p>Fixtures live in {@code src/test/resources/album-fixtures/*.json}; with none
 * present the factory yields a single no-op test, so the suite stays green in repos
 * without fixtures.</p>
 */
class AlbumFixtureHarnessTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "album-fixtures");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RoomSurfaceCalc surfaceCalc = new RoomSurfaceCalc();
    private final ElectroTakeoffCalc electroCalc = new ElectroTakeoffCalc();

    @TestFactory
    Stream<DynamicTest> realAlbumFixtures() throws IOException {
        if (!Files.isDirectory(FIXTURES)) {
            return Stream.of(DynamicTest.dynamicTest("no fixtures — skipped", () -> {}));
        }
        List<Path> files = Files.list(FIXTURES)
                .filter(p -> p.toString().endsWith(".json"))
                .sorted()
                .toList();
        if (files.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("no fixtures — skipped", () -> {}));
        }
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(),
                () -> check(p)));
    }

    private void check(Path file) {
        AlbumExtraction ex;
        try {
            ex = mapper.readValue(Files.readString(file), AlbumExtraction.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // --- surfaces ---------------------------------------------------------
        RoomSurfaceCalc.Result surfaces = surfaceCalc.calculate(ex);
        assertThat(surfaces.totalFloorM2()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(surfaces.totalWallsNetM2()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        for (RoomSurfaceCalc.RoomSurfaces r : surfaces.rooms()) {
            if (r.wallsGrossM2() != null && r.wallsNetM2() != null) {
                assertThat(r.wallsNetM2()).isLessThanOrEqualTo(r.wallsGrossM2());
            }
            if (r.plinthM() != null && r.perimeterM() != null) {
                assertThat(r.plinthM()).isLessThanOrEqualTo(r.perimeterM());
            }
        }

        // --- electro -----------------------------------------------------------
        ElectroTakeoffCalc.Result electro =
                electroCalc.calculate(ex, ElectroTakeoffCalc.Config.defaults());
        electro.totalsByMark().values().forEach(m -> assertThat(m).isNotNegative());
        electro.purchaseByMark().forEach((mark, bundled) ->
                assertThat(bundled).isGreaterThanOrEqualTo(electro.totalsByMark().get(mark)));
        assertThat(electro.chaseM()).isNotNegative();
        assertThat(electro.backboxes()).isNotNegative();

        // --- eyeball summary ------------------------------------------------------
        System.out.printf("=== %s: %s, поверхів %d, площа %s ===%n",
                file.getFileName(),
                ex.meta() == null ? "?" : ex.meta().projectName(),
                ex.meta() == null ? 0 : ex.meta().floors(),
                ex.meta() == null ? "?" : String.valueOf(ex.meta().totalAreaM2()));
        System.out.printf("  ПЛОЩІ: кімнат %d | підлога %s м² | стіни нетто %s м² | плінтус %s м | відкоси %s п.м%n",
                surfaces.rooms().size(), surfaces.totalFloorM2(), surfaces.totalWallsNetM2(),
                surfaces.totalPlinthM(), surfaces.totalRevealsM());
        surfaces.warnings().forEach(w -> System.out.println("  [площі] " + w));
        System.out.printf("  ЕЛЕКТРИКА: ліній %d | кабель %s | штроби %d м | підрозетники %d%n",
                electro.lines().size(), electro.totalsByMark(), electro.chaseM(),
                electro.backboxes());
        electro.openQuestions().forEach(q -> System.out.println("  [електрика?] " + q));
        electro.warnings().forEach(w -> System.out.println("  [електрика] " + w));
        if (ex.missing() != null) {
            ex.missing().forEach(m -> System.out.println("  [відсутнє] " + m));
        }
    }
}
