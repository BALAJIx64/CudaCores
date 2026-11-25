// GuardianGrid Authenticated Emergency Relay Edge Function (Supabase / Deno)
// Keeps TELEGRAM_BOT_TOKEN and GUARDIAN_HMAC_PEPPER exclusively on the backend.
// Required Supabase Secrets:
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, TELEGRAM_BOT_TOKEN, GUARDIAN_HMAC_PEPPER
// Optional:
//   GUARDIAN_DEFAULT_CHAT_ID

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const TELEGRAM_BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const DEFAULT_CHAT_ID = Deno.env.get("GUARDIAN_DEFAULT_CHAT_ID") ?? "";
const HMAC_PEPPER = Deno.env.get("GUARDIAN_HMAC_PEPPER") ?? "";
const AUDIO_BUCKET = "guardian-audio-evidence";
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;

function jsonResp(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...extraHeaders },
  });
}

async function sha256Bytes(text: string): Promise<ArrayBuffer> {
  return await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
}

async function sha256Hex(text: string): Promise<string> {
  const buf = await sha256Bytes(text);
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function deriveAesKey(): Promise<CryptoKey> {
  if (!HMAC_PEPPER) {
    throw new Error("Server secret GUARDIAN_HMAC_PEPPER is not configured");
  }
  const keyMaterial = await sha256Bytes(HMAC_PEPPER);
  return await crypto.subtle.importKey("raw", keyMaterial, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

function toBase64(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function fromBase64(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function encryptDeviceSecret(rawSecret: string): Promise<string> {
  const key = await deriveAesKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    new TextEncoder().encode(rawSecret),
  );
  return `${toBase64(iv)}.${toBase64(new Uint8Array(ct))}`;
}

async function decryptDeviceSecret(packed: string): Promise<string> {
  const [ivB64, ctB64] = packed.split(".");
  if (!ivB64 || !ctB64) throw new Error("Malformed encrypted device key");
  const key = await deriveAesKey();
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(ivB64) },
    key,
    fromBase64(ctB64),
  );
  return new TextDecoder().decode(plain);
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

async function verifyRegisteredDeviceRequest(
  req: Request,
  supabase: SupabaseClient,
  canonicalSuffix: string,
): Promise<{ ok: boolean; status: number; deviceId?: string; reason?: string }> {
  const deviceId = req.headers.get("X-Guardian-Device-Id") ?? "";
  const tsRaw = req.headers.get("X-Guardian-Timestamp") ?? "";
  const nonce = req.headers.get("X-Guardian-Nonce") ?? "";
  const sig = req.headers.get("X-Guardian-Signature") ?? "";
  if (!deviceId || !tsRaw || !nonce || !sig) {
    return { ok: false, status: 401, reason: "Missing authentication headers" };
  }

  const ts = Number(tsRaw);
  if (!Number.isFinite(ts) || Math.abs(Date.now() - ts) > MAX_CLOCK_SKEW_MS) {
    return { ok: false, status: 401, reason: "Expired or invalid request timestamp" };
  }

  const { data: devRow, error: devErr } = await supabase
    .from("guardian_devices")
    .select("device_id, secret_ciphertext")
    .eq("device_id", deviceId)
    .maybeSingle();

  if (devErr || !devRow) {
    return { ok: false, status: 401, reason: "Device is not registered" };
  }

  const deviceSecret = await decryptDeviceSecret(devRow.secret_ciphertext);
  const expectedSig = await hmacSha256Hex(deviceSecret, `${tsRaw}|${nonce}|${canonicalSuffix}`);
  if (!timingSafeEqualHex(expectedSig, sig)) {
    return { ok: false, status: 401, reason: "Invalid device HMAC signature" };
  }

  // Anti-replay nonce check (unique per device_id + nonce within clock window)
  const { error: nonceErr } = await supabase
    .from("guardian_request_nonces")
    .insert({ device_id: deviceId, nonce });
  if (nonceErr) {
    return { ok: false, status: 401, reason: "Replay nonce already used" };
  }

  await supabase.from("guardian_devices").update({ last_seen_at: new Date().toISOString() }).eq("device_id", deviceId);
  return { ok: true, status: 200, deviceId };
}

async function verifyAuthorizedTargets(
  supabase: SupabaseClient,
  hostDeviceId: string,
  targetGuardianIds: string[],
): Promise<string[]> {
  if (targetGuardianIds.length === 0) return [];
  const { data } = await supabase
    .from("guardian_pairings")
    .select("guardian_device_id")
    .eq("host_device_id", hostDeviceId)
    .eq("is_active", true)
    .in("guardian_device_id", targetGuardianIds);
  return (data ?? []).map((r: { guardian_device_id: string }) => r.guardian_device_id);
}

Deno.serve(async (req: Request) => {
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY || !HMAC_PEPPER) {
    return jsonResp({ ok: false, error: "Server secrets misconfigured" }, 500);
  }

  const url = new URL(req.url);
  const path = url.pathname;
  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

  // 1. Register / rotate device credentials
  // First registration accepts device-generated per-install HMAC secret; re-registration requires existing valid signature.
  if (req.method === "POST" && path.endsWith("/register-device")) {
    const payload = await req.json();
    const deviceId = String(payload.deviceId ?? "").trim();
    const deviceName = String(payload.deviceName ?? "GuardianGrid Device").trim().slice(0, 64);
    const deviceSecret = String(payload.deviceSecret ?? "").trim();
    if (deviceId.length < 8 || deviceSecret.length < 32) {
      return jsonResp({ ok: false, error: "Invalid deviceId or insufficient secret entropy" }, 400);
    }

    const { data: existing } = await supabase
      .from("guardian_devices")
      .select("device_id")
      .eq("device_id", deviceId)
      .maybeSingle();

    if (existing) {
      const auth = await verifyRegisteredDeviceRequest(req, supabase, `register|${deviceId}`);
      if (!auth.ok) return jsonResp({ ok: false, error: "Device already registered; signature required to update" }, 403);
    }

    const encrypted = await encryptDeviceSecret(deviceSecret);
    const fingerprint = await sha256Hex(deviceSecret);
    const { error } = await supabase.from("guardian_devices").upsert({
      device_id: deviceId,
      device_name: deviceName,
      secret_ciphertext: encrypted,
      secret_fingerprint: fingerprint,
      last_seen_at: new Date().toISOString(),
    }, { onConflict: "device_id" });

    if (error) return jsonResp({ ok: false, error: error.message }, 500);
    return jsonResp({ ok: true, deviceId, registered: true }, 200);
  }

  // 2. Create single-use expiring QR pairing invitation (keeps permanent secrets off QR codes)
  if (req.method === "POST" && path.endsWith("/pairing/invite")) {
    const body = await req.json();
    const inviteId = String(body.inviteId ?? "").trim();
    const hostDeviceId = String(body.hostDeviceId ?? "").trim();
    const hostDeviceName = String(body.hostDeviceName ?? "GuardianGrid Device").trim().slice(0, 64);
    const inviteToken = String(body.inviteToken ?? "").trim();
    const expiresAtMs = Number(body.expiresAtEpochMs ?? "0");

    const auth = await verifyRegisteredDeviceRequest(req, supabase, `invite|${inviteId}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);
    if (auth.deviceId !== hostDeviceId) {
      return jsonResp({ ok: false, error: "Caller must own the host invitation" }, 403);
    }
    if (inviteId.length < 8 || inviteToken.length < 16 || !Number.isFinite(expiresAtMs) || expiresAtMs <= Date.now()) {
      return jsonResp({ ok: false, error: "Invalid or expired pairing invitation payload" }, 400);
    }

    const tokenHash = await sha256Hex(inviteToken);
    const { error } = await supabase.from("guardian_pairing_invitations").upsert({
      invite_id: inviteId,
      host_device_id: hostDeviceId,
      host_device_name: hostDeviceName,
      token_hash: tokenHash,
      expires_at: new Date(expiresAtMs).toISOString(),
      consumed_at: null,
      consumed_by_device_id: null,
    }, { onConflict: "invite_id" });

    if (error) return jsonResp({ ok: false, error: error.message }, 500);
    return jsonResp({ ok: true, inviteId, registered: true, expiresAtEpochMs: expiresAtMs }, 200);
  }

  // 2b. Validate, atomically consume single-use invitation, and confirm dual-device pairing
  if (req.method === "POST" && path.endsWith("/pairing/accept")) {
    const body = await req.json();
    const inviteId = String(body.inviteId ?? "").trim();
    const hostDeviceId = String(body.hostDeviceId ?? "").trim();
    const inviteToken = String(body.inviteToken ?? "").trim();

    const auth = await verifyRegisteredDeviceRequest(req, supabase, `accept|${inviteId}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);
    if (!inviteId || !hostDeviceId || !inviteToken) {
      return jsonResp({ ok: false, error: "Missing inviteId, hostDeviceId, or inviteToken" }, 400);
    }
    if (auth.deviceId === hostDeviceId) {
      return jsonResp({ ok: false, error: "Cannot pair a device with itself" }, 400);
    }

    const { data: inviteRow, error: invErr } = await supabase
      .from("guardian_pairing_invitations")
      .select("invite_id, host_device_id, host_device_name, token_hash, expires_at, consumed_at")
      .eq("invite_id", inviteId)
      .maybeSingle();

    if (invErr || !inviteRow) {
      return jsonResp({ ok: false, error: "Pairing invitation not found" }, 404);
    }
    if (inviteRow.host_device_id !== hostDeviceId) {
      return jsonResp({ ok: false, error: "Host device identity mismatch" }, 403);
    }
    if (inviteRow.consumed_at) {
      return jsonResp({ ok: false, error: "Pairing invitation has already been used" }, 409);
    }
    if (new Date(inviteRow.expires_at).getTime() <= Date.now()) {
      return jsonResp({ ok: false, error: "Pairing invitation has expired" }, 410);
    }

    const suppliedHash = await sha256Hex(inviteToken);
    if (!timingSafeEqualHex(suppliedHash, inviteRow.token_hash)) {
      return jsonResp({ ok: false, error: "Invalid pairing invitation token" }, 403);
    }

    // Atomic single-use consumption check to block parallel replay
    const { data: consumedRow, error: consumeErr } = await supabase
      .from("guardian_pairing_invitations")
      .update({
        consumed_at: new Date().toISOString(),
        consumed_by_device_id: auth.deviceId,
      })
      .eq("invite_id", inviteId)
      .is("consumed_at", null)
      .select("invite_id")
      .maybeSingle();

    if (consumeErr || !consumedRow) {
      return jsonResp({ ok: false, error: "Pairing invitation was already consumed" }, 409);
    }

    const { error: pairErr } = await supabase.from("guardian_pairings").upsert({
      host_device_id: hostDeviceId,
      guardian_device_id: auth.deviceId,
      is_active: true,
      paired_at: new Date().toISOString(),
    }, { onConflict: "host_device_id,guardian_device_id" });

    if (pairErr) return jsonResp({ ok: false, error: pairErr.message }, 500);
    return jsonResp({
      ok: true,
      pairingConfirmed: true,
      inviteId,
      hostDeviceId,
      hostDeviceName: inviteRow.host_device_name,
      guardianDeviceId: auth.deviceId,
    }, 200);
  }

  // 2c. List confirmed active pairings for authenticated caller (Sender sees Guardians; Receiver sees Senders)
  if (req.method === "GET" && path.endsWith("/pairings")) {
    const auth = await verifyRegisteredDeviceRequest(req, supabase, `pairings|${url.searchParams.get("deviceId") ?? "self"}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);

    const { data: asHost } = await supabase
      .from("guardian_pairings")
      .select("guardian_device_id, paired_at, guardian_devices!guardian_pairings_guardian_device_id_fkey(device_name, last_seen_at)")
      .eq("host_device_id", auth.deviceId)
      .eq("is_active", true);

    const { data: asGuardian } = await supabase
      .from("guardian_pairings")
      .select("host_device_id, paired_at, guardian_devices!guardian_pairings_host_device_id_fkey(device_name, last_seen_at)")
      .eq("guardian_device_id", auth.deviceId)
      .eq("is_active", true);

    const guardians = (asHost ?? []).map((r: Record<string, unknown>) => {
      const dev = r.guardian_devices as { device_name?: string; last_seen_at?: string } | null;
      return {
        deviceId: r.guardian_device_id,
        deviceName: dev?.device_name ?? "Paired Guardian",
        pairedAt: r.paired_at,
        lastSeenAt: dev?.last_seen_at ?? r.paired_at,
      };
    });

    const hosts = (asGuardian ?? []).map((r: Record<string, unknown>) => {
      const dev = r.guardian_devices as { device_name?: string; last_seen_at?: string } | null;
      return {
        deviceId: r.host_device_id,
        deviceName: dev?.device_name ?? "Protected Host",
        pairedAt: r.paired_at,
        lastSeenAt: dev?.last_seen_at ?? r.paired_at,
      };
    });

    return jsonResp({ ok: true, deviceId: auth.deviceId, guardians, hosts }, 200);
  }

  // 2d. Direct Host <-> Guardian pairing link (legacy / explicit endpoint)
  if (req.method === "POST" && path.endsWith("/pair")) {
    const body = await req.json();
    const hostDeviceId = String(body.hostDeviceId ?? "").trim();
    const guardianDeviceId = String(body.guardianDeviceId ?? "").trim();
    const auth = await verifyRegisteredDeviceRequest(req, supabase, `pair|${hostDeviceId}|${guardianDeviceId}`);
    if (!auth.ok) return jsonResp({ ok: false, error: auth.reason }, auth.status);
    if (auth.deviceId !== hostDeviceId && auth.deviceId !== guardianDeviceId) {
      return jsonResp({ ok: false, error: "Caller must be one of the paired devices" }, 403);
    }

    const { error } = await supabase.from("guardian_pairings").upsert({
      host_device_id: hostDeviceId,
      guardian_device_id: guardianDeviceId,
      is_active: true,
      paired_at: new Date().toISOString(),
    }, { onConflict: "host_device_id,guardian_device_id" });

    if (error) return jsonResp({ ok: false, error: error.message }, 500);
    return jsonResp({ ok: true, hostDeviceId, guardianDeviceId, paired: true }, 200);
  }

  // 3. Dispatch Emergency Alert Envelope
  if (req.method === "POST" && path.endsWith("/alert")) {
    const body = await req.json();
    const incidentId = String(body.incidentId ?? "").trim();
    const distressType = String(body.distressType ?? "MANUAL_SOS").trim();
    const confidence = Number(body.confidence ?? 1.0);
    const emergencyMessage = String(body.emergencyMessage ?? "GuardianGrid Emergency Alert");
    const requestedTargets: string[] = Array.isArray(body.targetGuardianDeviceIds)
      ? body.targetGuardianDeviceIds.map((x: unknown) => String(x))
      : [];

    const auth = await verifyRegisteredDeviceRequest(req, supabase, `alert|${incidentId}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);

    if (requestedTargets.length > 0) {
      const authorizedTargets = await verifyAuthorizedTargets(supabase, auth.deviceId, requestedTargets);
      if (authorizedTargets.length === 0) {
        return jsonResp({ ok: false, error: "No active authorized pairings for requested target guardian(s)" }, 403);
      }
    }

    const { error } = await supabase.from("emergency_incidents").upsert({
      incident_id: incidentId,
      host_device_id: auth.deviceId,
      distress_type: distressType,
      confidence,
      emergency_message: emergencyMessage,
      latitude: body.latitude ?? null,
      longitude: body.longitude ?? null,
      accuracy_meters: body.accuracyMeters ?? null,
      status: "ACTIVE",
    }, { onConflict: "incident_id" });

    if (error) return jsonResp({ ok: false, error: error.message }, 500);

    const chatId = String(body.chatId ?? DEFAULT_CHAT_ID).trim();
    let telegramMessageId: string | null = null;
    if (TELEGRAM_BOT_TOKEN && chatId) {
      const mapsLink = (body.latitude != null && body.longitude != null)
        ? `https://maps.google.com/?q=${body.latitude},${body.longitude}`
        : "UNAVAILABLE";
      const tgText = `🚨 GUARDIANGRID EMERGENCY (${distressType})\nIncident: ${incidentId}\nLocation: ${mapsLink}\n${emergencyMessage}`;
      const tgResp = await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ chat_id: chatId, text: tgText }),
      });
      if (tgResp.status === 429) {
        const retryJson = await tgResp.json().catch(() => ({}));
        const retryAfter = retryJson?.parameters?.retry_after ?? 5;
        return jsonResp({ ok: false, error_code: 429, parameters: { retry_after: retryAfter } }, 429, { "Retry-After": String(retryAfter) });
      }
      if (tgResp.ok) {
        const tgBody = await tgResp.json();
        telegramMessageId = String(tgBody?.result?.message_id ?? "");
      }
    }

    return jsonResp({ ok: true, incidentId, messageId: telegramMessageId }, 200);
  }

  // 4. Upload Sequential Emergency Audio WAV Chunk & Relay via Server-Side Telegram Token
  if (req.method === "POST" && path.endsWith("/audio-chunk")) {
    const form = await req.formData();
    const incidentId = String(form.get("incidentId") ?? "").trim();
    const chunkSequence = Number(form.get("chunkSequence") ?? "0");
    const isPreTrigger = String(form.get("isPreTrigger") ?? "false") === "true";
    const isFinal = String(form.get("isFinal") ?? "false") === "true";
    const durationMs = Number(form.get("durationMs") ?? "0");
    const capturedAtMs = Number(form.get("capturedAtEpochMs") ?? String(Date.now()));
    const caption = String(form.get("caption") ?? `Incident ${incidentId} Audio`);
    const targetChatId = String(form.get("chatId") ?? DEFAULT_CHAT_ID).trim();
    const file = form.get("audio") as File | null;

    const auth = await verifyRegisteredDeviceRequest(req, supabase, `${incidentId}|${chunkSequence}|${file?.size ?? 0}`);
    if (!auth.ok || !auth.deviceId) {
      return jsonResp({ ok: false, error: auth.reason }, auth.status);
    }
    if (!incidentId || !file) {
      return jsonResp({ ok: false, error: "Missing incidentId or audio WAV file" }, 400);
    }

    // Verify incident ownership (prevent unauthorized device injecting audio into another host's incident)
    const { data: existingInc } = await supabase
      .from("emergency_incidents")
      .select("host_device_id")
      .eq("incident_id", incidentId)
      .maybeSingle();

    if (existingInc && existingInc.host_device_id !== auth.deviceId) {
      return jsonResp({ ok: false, error: "Forbidden: caller does not own this incident" }, 403);
    }
    if (!existingInc) {
      await supabase.from("emergency_incidents").insert({
        incident_id: incidentId,
        host_device_id: auth.deviceId,
        distress_type: String(form.get("distressType") ?? "EMERGENCY"),
        confidence: 1.0,
        status: "ACTIVE",
      });
    }

    const objectPath = `${auth.deviceId}/${incidentId}/chunk_${String(chunkSequence).padStart(4, "0")}.wav`;
    const wavBytes = new Uint8Array(await file.arrayBuffer());
    const uploadRes = await supabase.storage.from(AUDIO_BUCKET).upload(objectPath, wavBytes, {
      contentType: "audio/wav",
      upsert: true,
    });
    if (uploadRes.error) {
      return jsonResp({ ok: false, error: uploadRes.error.message }, 500);
    }

    let telegramMsgId: string | null = null;
    let telegramDelivered = false;
    if (TELEGRAM_BOT_TOKEN && targetChatId) {
      const tgForm = new FormData();
      tgForm.append("chat_id", targetChatId);
      tgForm.append("caption", caption);
      tgForm.append("document", new Blob([wavBytes], { type: "audio/wav" }), file.name || `chunk_${chunkSequence}.wav`);
      const tgResp = await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument`, {
        method: "POST",
        body: tgForm,
      });
      if (tgResp.status === 429) {
        const retryJson = await tgResp.json().catch(() => ({}));
        const retryAfter = retryJson?.parameters?.retry_after ?? 5;
        return jsonResp(
          { ok: false, error_code: 429, parameters: { retry_after: retryAfter } },
          429,
          { "Retry-After": String(retryAfter) },
        );
      }
      if (!tgResp.ok) {
        const errBody = await tgResp.text().catch(() => "");
        return jsonResp({ ok: false, error: `Telegram relay failed (${tgResp.status}): ${errBody.slice(0, 120)}` }, 502);
      }
      const tgBody = await tgResp.json();
      telegramMsgId = String(tgBody?.result?.message_id ?? "");
      telegramDelivered = true;
    }

    await supabase.from("emergency_audio_chunks").upsert({
      incident_id: incidentId,
      device_id: auth.deviceId,
      chunk_sequence: chunkSequence,
      is_pre_trigger: isPreTrigger,
      is_final: isFinal,
      duration_ms: durationMs,
      captured_at: new Date(capturedAtMs).toISOString(),
      storage_object_path: objectPath,
      telegram_delivered: telegramDelivered,
      telegram_message_id: telegramMsgId,
    }, { onConflict: "incident_id,chunk_sequence" });

    return jsonResp({
      ok: true,
      incidentId,
      chunkSequence,
      storagePath: objectPath,
      telegramDelivered,
      messageId: telegramMsgId,
    }, 200);
  }

  // 5. Mark Incident Recording Completed & Dispatch Completion Summary
  if (req.method === "POST" && path.endsWith("/complete")) {
    const payload = await req.json();
    const incidentId = String(payload.incidentId ?? "").trim();
    const auth = await verifyRegisteredDeviceRequest(req, supabase, `complete|${incidentId}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);

    const { data: existingInc } = await supabase
      .from("emergency_incidents")
      .select("host_device_id")
      .eq("incident_id", incidentId)
      .maybeSingle();

    if (existingInc && existingInc.host_device_id !== auth.deviceId) {
      return jsonResp({ ok: false, error: "Forbidden: caller does not own incident" }, 403);
    }

    await supabase.from("emergency_incidents").update({
      status: "COMPLETED",
      total_chunks: Number(payload.totalChunks ?? 0),
      total_duration_ms: Number(payload.totalDurationMs ?? 0),
      completed_at: new Date().toISOString(),
    }).eq("incident_id", incidentId);

    const chatId = String(payload.chatId ?? DEFAULT_CHAT_ID).trim();
    let msgId: string | null = null;
    if (TELEGRAM_BOT_TOKEN && chatId) {
      const tgResp = await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          chat_id: chatId,
          text: String(payload.summaryMessage ?? `Incident ${incidentId} recording completed.`),
        }),
      });
      if (tgResp.ok) {
        const tgBody = await tgResp.json();
        msgId = String(tgBody?.result?.message_id ?? "");
      }
    }
    return jsonResp({ ok: true, incidentId, status: "COMPLETED", messageId: msgId }, 200);
  }

  // 6. Paired Guardian Acknowledgment of Emergency Alert
  if (req.method === "POST" && path.endsWith("/ack")) {
    const body = await req.json();
    const incidentId = String(body.incidentId ?? "").trim();
    const guardianName = String(body.guardianName ?? "Paired Guardian").trim().slice(0, 64);

    const auth = await verifyRegisteredDeviceRequest(req, supabase, `ack|${incidentId}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);
    if (!incidentId) return jsonResp({ ok: false, error: "Missing incidentId" }, 400);

    const { data: inc } = await supabase
      .from("emergency_incidents")
      .select("incident_id, host_device_id")
      .eq("incident_id", incidentId)
      .maybeSingle();

    if (!inc) return jsonResp({ ok: false, error: "Incident not found" }, 404);

    const authorized = await verifyAuthorizedTargets(supabase, inc.host_device_id, [auth.deviceId]);
    if (authorized.length === 0) {
      return jsonResp({ ok: false, error: "Forbidden: caller is not an active paired guardian for this host" }, 403);
    }

    const ackAt = new Date().toISOString();
    const { error: ackErr } = await supabase
      .from("emergency_incidents")
      .update({
        status: "ACKNOWLEDGED",
        acknowledged_at: ackAt,
        acknowledged_by_device_id: auth.deviceId,
        acknowledged_by_name: guardianName,
      })
      .eq("incident_id", incidentId);

    if (ackErr) return jsonResp({ ok: false, error: ackErr.message }, 500);
    return jsonResp({ ok: true, incidentId, acknowledged: true, acknowledgedAt: ackAt }, 200);
  }

  // 7. Paired Guardian Authorized Audio/Alert Retrieval (returns short-lived signed URLs ONLY for paired hosts)
  if (req.method === "GET" && path.endsWith("/inbox")) {
    const auth = await verifyRegisteredDeviceRequest(req, supabase, `inbox|${url.searchParams.get("incidentId") ?? "all"}`);
    if (!auth.ok || !auth.deviceId) return jsonResp({ ok: false, error: auth.reason }, auth.status);

    const { data: pairings } = await supabase
      .from("guardian_pairings")
      .select("host_device_id")
      .eq("guardian_device_id", auth.deviceId)
      .eq("is_active", true);

    const hostIds = (pairings ?? []).map((p: { host_device_id: string }) => p.host_device_id);
    if (hostIds.length === 0) {
      return jsonResp({ ok: true, alerts: [] }, 200);
    }

    const { data: hostDevices } = await supabase
      .from("guardian_devices")
      .select("device_id, device_name")
      .in("device_id", hostIds);
    const hostNames = new Map<string, string>(
      (hostDevices ?? []).map((d: { device_id: string; device_name: string }) => [d.device_id, d.device_name]),
    );

    const { data: incidents } = await supabase
      .from("emergency_incidents")
      .select("*")
      .in("host_device_id", hostIds)
      .order("created_at", { ascending: false })
      .limit(10);

    const alerts = [];
    for (const inc of incidents ?? []) {
      const { data: chunks } = await supabase
        .from("emergency_audio_chunks")
        .select("*")
        .eq("incident_id", inc.incident_id)
        .order("chunk_sequence", { ascending: true });

      const signedChunks = [];
      for (const ch of chunks ?? []) {
        const { data: signed } = await supabase.storage
          .from(AUDIO_BUCKET)
          .createSignedUrl(ch.storage_object_path, 300); // 5-min ephemeral private URL
        const objName = String(ch.storage_object_path ?? "").split("/").pop() || `chunk_${ch.chunk_sequence}.wav`;
        signedChunks.push({
          chunkSequence: ch.chunk_sequence,
          isPreTrigger: ch.is_pre_trigger,
          isFinal: ch.is_final,
          fileName: objName,
          durationMs: ch.duration_ms,
          capturedAt: ch.captured_at,
          signedAudioUrl: signed?.signedUrl ?? null,
        });
      }
      alerts.push({
        incidentId: inc.incident_id,
        hostDeviceId: inc.host_device_id,
        hostDeviceName: hostNames.get(inc.host_device_id) ?? "Protected Device",
        distressType: inc.distress_type,
        confidence: inc.confidence,
        emergencyMessage: inc.emergency_message,
        latitude: inc.latitude,
        longitude: inc.longitude,
        accuracyMeters: inc.accuracy_meters,
        isAcknowledged: inc.status === "ACKNOWLEDGED" || Boolean(inc.acknowledged_at),
        createdAt: inc.created_at,
        chunks: signedChunks,
      });
    }
    return jsonResp({ ok: true, alerts }, 200);
  }

  return jsonResp({ ok: false, error: "Unsupported route" }, 404);
});
