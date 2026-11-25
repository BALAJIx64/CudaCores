-- GuardianGrid Supabase Migration 002: BLE Emergency-Alert Relay Architecture
-- Target project: CudaCores (yhzvpuycztuwdqttpoid, ap-northeast-1)
--
-- Tables:
-- 1. ble_emergency_tokens: Short-lived signing tokens issued to sender devices
-- 2. ble_alert_nonces: One-time nonces for anti-replay protection
-- 3. ble_emergency_alerts: Idempotent emergency alert records received via BLE relay
-- 4. guardian_emergency_contacts: Primary emergency contacts associated with accounts
-- 5. emergency_sms_dispatches: Server-side SMS delivery attempts and provider outcomes

-- 1. Short-Lived BLE Emergency Signing Tokens
-- Tokens are bound to a specific account_id and sender_device_id.
-- token_ciphertext is AES-256-GCM encrypted using server-side GUARDIAN_HMAC_PEPPER.
CREATE TABLE IF NOT EXISTS public.ble_emergency_tokens (
    token_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL,
    device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    token_ciphertext TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ble_tokens_lookup
    ON public.ble_emergency_tokens (account_id, device_id, expires_at);

-- 2. Anti-Replay Nonce Cache
-- Rejects duplicate or replayed nonces per account within validity window.
CREATE TABLE IF NOT EXISTS public.ble_alert_nonces (
    account_id UUID NOT NULL,
    nonce TEXT NOT NULL,
    device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    PRIMARY KEY (account_id, nonce)
);

CREATE INDEX IF NOT EXISTS idx_ble_nonces_expiry
    ON public.ble_alert_nonces (expires_at);

-- 3. BLE Emergency Alerts Table
-- Persists validated alert packets forwarded by relay phones.
CREATE TABLE IF NOT EXISTS public.ble_emergency_alerts (
    alert_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL,
    sender_device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    relay_device_id TEXT,
    token_id TEXT REFERENCES public.ble_emergency_tokens(token_id),
    protocol_version INT NOT NULL DEFAULT 1,
    alert_type TEXT NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy_meters REAL,
    emergency_message TEXT,
    signature TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'RECEIVED', -- RECEIVED | SMS_SENT | SMS_FAILED | ACKNOWLEDGED
    raw_packet JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ble_alerts_account
    ON public.ble_emergency_alerts (account_id, created_at DESC);

-- 4. Primary Emergency Contacts Table
-- Stores user emergency contacts; backend uses this exclusively for contact lookup during BLE relay alert.
CREATE TABLE IF NOT EXISTS public.guardian_emergency_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL,
    contact_name TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    relationship TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_emergency_contacts_lookup
    ON public.guardian_emergency_contacts (account_id, is_primary);

-- 5. Server-Side SMS Dispatches Log
-- Records all SMS dispatch attempts, provider responses, and errors.
CREATE TABLE IF NOT EXISTS public.emergency_sms_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id TEXT NOT NULL REFERENCES public.ble_emergency_alerts(alert_id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    recipient_phone TEXT NOT NULL,
    provider TEXT NOT NULL, -- TWILIO | VONAGE | GENERIC_HTTP
    provider_message_id TEXT,
    status TEXT NOT NULL, -- SENT | FAILED | PROVIDER_ERROR | REJECTED
    error_message TEXT,
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sms_dispatches_alert
    ON public.emergency_sms_dispatches (alert_id, status);

-- 6. Strict Row-Level Security (RLS)
ALTER TABLE public.ble_emergency_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ble_alert_nonces ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ble_emergency_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.guardian_emergency_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.emergency_sms_dispatches ENABLE ROW LEVEL SECURITY;

-- Deny all direct anonymous / authenticated REST access.
-- All access flows through the authenticated Edge Function with SERVICE_ROLE_KEY.
DROP POLICY IF EXISTS "deny_anon_ble_tokens" ON public.ble_emergency_tokens;
CREATE POLICY "deny_anon_ble_tokens" ON public.ble_emergency_tokens FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_ble_nonces" ON public.ble_alert_nonces;
CREATE POLICY "deny_anon_ble_nonces" ON public.ble_alert_nonces FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_ble_alerts" ON public.ble_emergency_alerts;
CREATE POLICY "deny_anon_ble_alerts" ON public.ble_emergency_alerts FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_contacts" ON public.guardian_emergency_contacts;
CREATE POLICY "deny_anon_contacts" ON public.guardian_emergency_contacts FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_sms_dispatches" ON public.emergency_sms_dispatches;
CREATE POLICY "deny_anon_sms_dispatches" ON public.emergency_sms_dispatches FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

-- 7. Periodic Cleanup of Expired Nonces and Tokens
CREATE OR REPLACE FUNCTION public.purge_expired_ble_records()
RETURNS TABLE(deleted_nonces INT, deleted_tokens INT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_nonces INT := 0;
    v_tokens INT := 0;
BEGIN
    WITH del_n AS (
        DELETE FROM public.ble_alert_nonces
        WHERE expires_at < NOW()
        RETURNING 1
    )
    SELECT COUNT(*) INTO v_nonces FROM del_n;

    WITH del_t AS (
        DELETE FROM public.ble_emergency_tokens
        WHERE expires_at < NOW() - INTERVAL '30 days'
        RETURNING 1
    )
    SELECT COUNT(*) INTO v_tokens FROM del_t;

    RETURN QUERY SELECT v_nonces, v_tokens;
END;
$$;
