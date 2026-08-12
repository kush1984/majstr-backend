-- The PRO trial goes from 15 days to 30, and everyone already mid-trial gets the extra days.
--
-- Why: the AI-calling flows (plan/sketch recognition, project import — both call the vision LLM)
-- are temporarily hidden to cut AI spend. PRO now sells mainly on the non-AI features, so the
-- trial needs more room to let a master actually run an object through them before it lapses.
--
-- The trial LENGTH itself lives in config (app.billing.trial-days), so this migration only fixes
-- up the masters who are mid-trial right now — same shape as V86 (5 → 15 days).

DO $$
DECLARE
    v_extended int;
BEGIN
    -- ============ WHO IS TOUCHED ==============================================
    --   · took the trial (trial_started_at IS NOT NULL);
    --   · is still on it — a paid plan that has not run out yet;
    --   · has NEVER paid. A master who bought PRO is on a subscription, not a trial, and his
    --     expiry date is something he paid for — moving it would be giving away a month.
    -- ==========================================================================
    UPDATE users u
    SET plan_expires_at = GREATEST(u.plan_expires_at, u.trial_started_at + INTERVAL '30 days')
    WHERE u.trial_started_at IS NOT NULL
      AND u.plan <> 'FREE'
      AND u.plan_expires_at IS NOT NULL
      AND u.plan_expires_at > now()
      AND NOT EXISTS (
          SELECT 1 FROM payments p
          WHERE p.user_id = u.id AND p.status = 'SUCCESS');
    GET DIAGNOSTICS v_extended = ROW_COUNT;

    -- GREATEST, not a plain assignment: a trial master may also hold days from a referral reward
    -- (30 days, granted on someone else's first payment), and recomputing from trial_started_at
    -- alone would take those away. This migration can only ever ADD time.
    --
    -- Deliberately NOT touched: trials that have already lapsed — same rule as V86.
    RAISE NOTICE 'V101: extended % live trial(s) to 30 days', v_extended;
END $$;
