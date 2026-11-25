-- GuardianGrid Supabase Backend Schema, Private Audio Storage & Retention Enforcement
-- Target project: CudaCores (yhzvpuycztuwdqttpoid, ap-northeast-1)

-- 1. Create PRIVATE Storage Bucket for emergency WAV audio evidence (never public)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'guardian-audio-evidence',
    'guardian-audio-evidence',
    FALSE,
    26214400, -- 25 MB max per object
    ARRAY['audio/wav', 'audio/x-wav', 'audio/wave']
)
ON CONFLICT (id) DO UPDATE SET
    public = FALSE,
    file_size_limit = 26214400,
    allowed_mime_types = ARRAY['audio/wav', 'audio/x-wav', 'audio/wave'];

-- 2. Cryptographic Device Identity Registry (Login-free per-device credential store)
-- secret_ciphertext is AES-256-GCM encrypted using server-side GUARDIAN_HMAC_PEPPER.
CREATE TABLE IF NOT EXISTS public.guardian_devices (
    device_id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    secret_fingerprint TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Authorized Host <-> Guardian Pairing Links
CREATE TABLE IF NOT EXISTS public.guardian_pairings (
    host_device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    guardian_device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    paired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (host_device_id, guardian_device_id)
);

-- 4. Emergency Incidents Table
CREATE TABLE IF NOT EXISTS public.emergency_incidents (
    incident_id TEXT PRIMARY KEY,
    host_device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    distress_type TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    emergency_message TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy_meters REAL,
    status TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | COMPLETED | ACKNOWLEDGED
    total_chunks INT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days')
);

-- 5. Sequential Emergency Audio Chunks Metadata & Retention Table
CREATE TABLE IF NOT EXISTS public.emergency_audio_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id TEXT NOT NULL REFERENCES public.emergency_incidents(incident_id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES public.guardian_devices(device_id) ON DELETE CASCADE,
    chunk_sequence INT NOT NULL, -- 0 = Pre-trigger 10s, 1..N = Post-trigger 10s chunks
    is_pre_trigger BOOLEAN NOT NULL DEFAULT FALSE,
    is_final BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    storage_object_path TEXT NOT NULL,
    telegram_delivered BOOLEAN NOT NULL DEFAULT FALSE,
    telegram_message_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
    UNIQUE (incident_id, chunk_sequence)
);

-- 6. Anti-Replay Nonce Window Cache (optional replay defense)
CREATE TABLE IF NOT EXISTS public.guardian_request_nonces (
    device_id TEXT NOT NULL,
    nonce TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '10 minutes'),
    PRIMARY KEY (device_id, nonce)
);

-- 7. Enable Strict Row-Level Security (RLS) across all tables
ALTER TABLE public.guardian_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.guardian_pairings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.emergency_incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.emergency_audio_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.guardian_request_nonces ENABLE ROW LEVEL SECURITY;

-- Deny all direct anonymous / authenticated REST reads; all Android <-> Supabase access flows
-- exclusively through authenticated `emergency-relay` Edge Function after HMAC verification.
DROP POLICY IF EXISTS "deny_anon_devices" ON public.guardian_devices;
CREATE POLICY "deny_anon_devices" ON public.guardian_devices FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_pairings" ON public.guardian_pairings;
CREATE POLICY "deny_anon_pairings" ON public.guardian_pairings FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_incidents" ON public.emergency_incidents;
CREATE POLICY "deny_anon_incidents" ON public.emergency_incidents FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_chunks" ON public.emergency_audio_chunks;
CREATE POLICY "deny_anon_chunks" ON public.emergency_audio_chunks FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS "deny_anon_nonces" ON public.guardian_request_nonces;
CREATE POLICY "deny_anon_nonces" ON public.guardian_request_nonces FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

-- 8. Storage RLS: Private bucket access denied to anon & regular authenticated JWT roles
DROP POLICY IF EXISTS "deny_public_audio_bucket" ON storage.objects;
CREATE POLICY "deny_public_audio_bucket"
ON storage.objects
FOR ALL
TO anon, authenticated
USING (bucket_id <> 'guardian-audio-evidence')
WITH CHECK (bucket_id <> 'guardian-audio-evidence');

-- 9. Automatic Retention Cleanup Routine (Invoke via pg_cron or scheduled Edge Function)
CREATE OR REPLACE FUNCTION public.purge_expired_guardian_audio(retention_days INT DEFAULT 7)
RETURNS TABLE(deleted_chunks INT, deleted_incidents INT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_chunks INT := 0;
    v_incidents INT := 0;
BEGIN
    DELETE FROM public.guardian_request_nonces
    WHERE expires_at < NOW();

    DELETE FROM storage.objects
    WHERE bucket_id = 'guardian-audio-evidence'
      AND name IN (
          SELECT storage_object_path
          FROM public.emergency_audio_chunks
          WHERE expires_at < NOW()
             OR created_at < NOW() - MAKE_INTERVAL(days => retention_days)
      );

    WITH del_c AS (
        DELETE FROM public.emergency_audio_chunks
        WHERE expires_at < NOW()
           OR created_at < NOW() - MAKE_INTERVAL(days => retention_days)
        RETURNING 1
    )
    SELECT COUNT(*) INTO v_chunks FROM del_c;

    WITH del_i AS (
        DELETE FROM public.emergency_incidents
        WHERE expires_at < NOW()
           OR created_at < NOW() - MAKE_INTERVAL(days => retention_days)
        RETURNING 1
    )
    SELECT COUNT(*) INTO v_incidents FROM del_i;

    RETURN QUERY SELECT v_chunks, v_incidents;
END;
$$;
