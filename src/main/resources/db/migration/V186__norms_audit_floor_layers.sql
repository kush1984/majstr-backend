-- =================================================================================================
-- V186 — norms audit (2026-11-02), priority 1: two floor layers bought wrong
--
-- Owner ruling (2026-11-03) on the preview in docs/iteration-norms-audit.md, checked against the
-- manufacturers' sheets (Ceresit CN 83/87/178, Knauf Боден, Kreisel/Nanofix, Sikafloor-263 SL,
-- Mapefloor I 300 SL, Sikafloor-161):
--
--   * «Наливна підлога — вирівнювальний шар 10–50 мм» suggested 5 mm — a leftover of V161's rename,
--     below the range its own name states. A levelling layer in a flat is 15–30 mm in practice
--     (deeper goes under a sand-cement screed first): suggest 20 mm. 1,8 kg/m²·mm stays — the
--     cement and gypsum mixes run 1,7–2,0. The two thin self-levelling positions keep 5 mm.
--   * «Нанесення епоксидного покриття» was a flat 1,0 kg/m² — a roller coat, while the position
--     (and every Ukrainian price list) means a self-levelling layer of 1–3 mm. It is now per mm of
--     THICKNESS, 2 mm suggested: resin 1,0 kg/m²·mm + a NEW material, quartz sand, 0,6 kg/m²·mm —
--     1 : 0,6 by weight, inside Sikafloor-263 SL's 1 : 0,5–1 : 1; 1,6 kg/m²·mm of mix in all.
--     The primer stays 0,4 kg/m² per coat (Sika 0,35–0,55, Mapei 0,3–0,7).
--
-- LIBRARY ROWS ONLY (owner_id IS NULL). A master's fork is his own figure and is left alone — the
-- owner's ruling for this round, so V143's «re-file the forks too» does not apply: nothing is
-- renamed or re-keyed, a fork keeps answering exactly as before. No catalog row is touched, so
-- V118's ranking is not re-run.
--
-- Preconditions only WARN and skip (V176's incident): a library row no longer where the audit found
-- it — an admin may have edited it — is left as it is.
-- =================================================================================================

UPDATE material_norm
   SET default_param = 20
 WHERE owner_id IS NULL
   AND trade = 'FLOORING'
   AND name_key = 'наливна підлога — вирівнювальний шар 10–50 мм'
   AND unit = 'M2'
   AND basis = 'THICKNESS'
   AND default_param = 5;

INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'QUARTZ_SAND', 'Пісок кварцовий (для епоксидної підлоги)', NULL, 'KG', 25.000, 'KG', 'мішок')
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    v_rows int;
BEGIN
    UPDATE material_norm n
       SET basis = 'THICKNESS', qty_per_unit = 1.0, default_param = 2
      FROM material m
     WHERE m.id = n.material_id AND m.code = 'EPOXY_COATING'
       AND n.owner_id IS NULL
       AND n.trade = 'FLOORING'
       AND n.name_key = 'нанесення епоксидного покриття'
       AND n.unit = 'M2'
       AND n.basis = 'QUANTITY'
       AND n.qty_per_unit = 1.0;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF v_rows = 0 THEN
        RAISE WARNING 'V186: the epoxy coating norm is not where the audit found it — left as it is, no quartz added';
        RETURN;
    END IF;

    INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, default_param, sort_order)
    SELECT gen_random_uuid(), 'FLOORING', 'нанесення епоксидного покриття', 'M2', m.id, 0.6, 'THICKNESS', 2, 2
      FROM material m
     WHERE m.code = 'QUARTZ_SAND'
    ON CONFLICT DO NOTHING;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM material_norm
                    WHERE owner_id IS NULL AND name_key = 'наливна підлога — вирівнювальний шар 10–50 мм'
                      AND default_param = 20) THEN
        RAISE WARNING 'V186: the 10–50 mm levelling layer still does not suggest 20 mm';
    END IF;
END $$;
