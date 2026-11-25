-- GuardianGrid Supabase Migration 003: Standalone Live Location Sharing
-- Target project: CudaCores (yhzvpuycztuwdqttpoid, ap-northeast-1)
-- Completely separate from SOS emergency incidents and audio evidence.

CREATE TABLE IF NOT EXISTS public.live_location_sessions (
    session_id TEXT PRIMARY KEY,
    host_device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    share_token_hash TEXT NOT NULL UNIQUE,
    recipient_phones JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_minutes INT NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy_meters REAL,
    status TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | STOPPED | EXPIRED
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    stopped_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_live_location_token_hash
    ON public.live_location_sessions (share_token_hash);

CREATE INDEX IF NOT EXISTS idx_live_location_host_status
    ON public.live_location_sessions (host_device_id, status);

ALTER TABLE public.live_location_sessions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "deny_anon_live_location" ON public.live_location_sessions;
CREATE POLICY "deny_anon_live_location"
    ON public.live_location_sessions
    FOR ALL
    TO anon, authenticated
    USING (FALSE)
    WITH CHECK (FALSE);
