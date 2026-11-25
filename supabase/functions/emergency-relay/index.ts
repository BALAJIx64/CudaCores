// GuardianGrid Authenticated Emergency Relay Edge Function (Supabase / Deno)
// Keeps TELEGRAM_BOT_TOKEN, GUARDIAN_HMAC_PEPPER, and SMS Provider credentials exclusively on the backend.
// Required Supabase Secrets:
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, TELEGRAM_BOT_TOKEN, GUARDIAN_HMAC_PEPPER
// Optional:
//   GUARDIAN_DEFAULT_CHAT_ID
//   TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER
//   SMS_WEBHOOK_URL, SMS_WEBHOOK_AUTH_TOKEN

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const TELEGRAM_BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const DEFAULT_CHAT_ID = Deno.env.get("GUARDIAN_DEFAULT_CHAT_ID") ?? "";
const HMAC_PEPPER = Deno.env.get("GUARDIAN_HMAC_PEPPER") ?? "";
const TWILIO_ACCOUNT_SID = Deno.env.get("TWILIO_ACCOUNT_SID") ?? "";
const TWILIO_AUTH_TOKEN = Deno.env.get("TWILIO_AUTH_TOKEN") ?? "";
const TWILIO_FROM_NUMBER = Deno.env.get("TWILIO_FROM_NUMBER") ?? "";
const SMS_WEBHOOK_URL = Deno.env.get("SMS_WEBHOOK_URL") ?? "";
const SMS_WEBHOOK_AUTH_TOKEN = Deno.env.get("SMS_WEBHOOK_AUTH_TOKEN") ?? "";

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

async function encryptSecret(rawSecret: string): Promise<string> {
  const key = await deriveAesKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    new TextEncoder().encode(rawSecret),
  );
  return `${toBase64(iv)}.${toBase64(new Uint8Array(ct))}`;
}

async function decryptSecret(packed: string): Promise<string> {
  const [ivB64, ctB64] = packed.split(".");
  if (!ivB64 || !ctB64) throw new Error("Malformed encrypted secret");
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

function randomHex(byteLength = 32): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Canonical BLE emergency-alert string representation for deterministic signature computation
export function buildBleCanonicalString(packet: {
  alertId: string;
  accountId: string;
  senderDeviceId: string;
  tokenId: string;
  issuedAt: number;
  expiresAt: number;
  nonce: string;
  alertType: string;
  latitude?: number | null;
  longitude?: number | null;
  accuracyMeters?: number | null;
  emergencyMessage?: string | null;
}): string {
  const latStr = packet.latitude != null ? String(packet.latitude) : "";
  const lonStr = packet.longitude != null ? String(packet.longitude) : "";
  const accStr = packet.accuracyMeters != null ? String(packet.accuracyMeters) : "";
  const msgStr = packet.emergencyMessage ?? "";
  return `v1|${packet.alertId}|${packet.accountId}|${packet.senderDeviceId}|${packet.tokenId}|${packet.issuedAt}|${packet.expiresAt}|${packet.nonce}|${packet.alertType}|${latStr}|${lonStr}|${accStr}|${msgStr}`;
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

  const deviceSecret = await decryptSecret(devRow.secret_ciphertext);
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

/**
 * Server-side SMS Dispatcher.
 * Uses Twilio if configured, or generic HTTP SMS gateway if configured.
 * Never simulates success.
 */
interface SmsDispatchResult {
  success: boolean;
  provider: string;
  providerMessageId?: string;
  errorMessage?: string;
}

async function dispatchServerSideSms(
  toPhoneNumber: string,
  messageBody: string,
): Promise<SmsDispatchResult> {
  // 1. Twilio Provider
  if (TWILIO_ACCOUNT_SID && TWILIO_AUTH_TOKEN && TWILIO_FROM_NUMBER) {
    try {
      const url = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_ACCOUNT_SID}/Messages.json`;
      const form = new URLSearchParams();
      form.append("To", toPhoneNumber);
      form.append("From", TWILIO_FROM_NUMBER);
      form.append("Body", messageBody);

      const authHeader = `Basic ${btoa(`${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}`)}`;
      const resp = await fetch(url, {
        method: "POST",
        headers: {
          "Authorization": authHeader,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: form.toString(),
      });

      const respJson = await resp.json().catch(() => ({}));
      if (resp.ok) {
        return {
          success: true,
          provider: "TWILIO",
          providerMessageId: respJson.sid || undefined,
        };
      } else {
        return {
          success: false,
          provider: "TWILIO",
          errorMessage: `Twilio error ${resp.status}: ${respJson.message || JSON.stringify(respJson)}`,
        };
      }
    } catch (e: unknown) {
      return {
        success: false,
        provider: "TWILIO",
        errorMessage: `Twilio network failure: ${(e as Error).message}`,
      };
    }
  }

  // 2. Generic HTTP SMS Gateway
  if (SMS_WEBHOOK_URL) {
    try {
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (SMS_WEBHOOK_AUTH_TOKEN) {
        headers["Authorization"] = `Bearer ${SMS_WEBHOOK_AUTH_TOKEN}`;
      }
      const resp = await fetch(SMS_WEBHOOK_URL, {
        method: "POST",
        headers,
        body: JSON.stringify({ to: toPhoneNumber, message: messageBody }),
      });
      const data = await resp.json().catch(() => ({}));
      if (resp.ok) {
        return {
          success: true,
          provider: "GENERIC_HTTP",
          providerMessageId: data.messageId || data.id || undefined,
        };
      } else {
        return {
          success: false,
          provider: "GENERIC_HTTP",
          errorMessage: `Generic HTTP SMS gateway returned status ${resp.status}`,
        };
      }
    } catch (e: unknown) {
      return {
        success: false,
        provider: "GENERIC_HTTP",
        errorMessage: `Generic HTTP SMS gateway error: ${(e as Error).message}`,
      };
    }
  }

  // 3. Neither provider is configured — fail explicitly. Never simulate success.
  return {
    success: false,
    provider: "NONE",
    errorMessage: "Server SMS provider credentials not configured (TWILIO_ACCOUNT_SID or SMS_WEBHOOK_URL required)",
  };
}

Deno.serve(async (req: Request) => {
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY || !HMAC_PEPPER) {
    return jsonResp({ ok: false, error: "Server secrets misconfigured" }, 500);
  }

  const url = new URL(req.url);
  const path = url.pathname;
  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

  // -------------------------------------------------------------
  // Route 1: Register / rotate device credentials
  // -------------------------------------------------------------
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

    const encrypted = await encryptSecret(deviceSecret);
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

  // -------------------------------------------------------------
  // Route 2: Register active Host <-> Guardian pairing authorization
  // -------------------------------------------------------------
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

  // -------------------------------------------------------------
  // Route 3: Issue Short-Lived BLE Emergency Signing Token (Sender Phone)
  // -------------------------------------------------------------
  if (req.method === "POST" && path.endsWith("/issue-ble-token")) {
    const body = await req.json();
    const accountId = String(body.accountId ?? "").trim();
    const deviceId = String(body.deviceId ?? "").trim();
    const validityHours = Math.min(Math.max(Number(body.validityHours ?? 72), 1), 168); // 1h to 7 days, default 72h

    if (!accountId || !deviceId) {
      return jsonResp({ ok: false, error: "Missing accountId or deviceId" }, 400);
    }

    // Validate device identity via HMAC request header
    const auth = await verifyRegisteredDeviceRequest(req, supabase, `issue-ble-token|${accountId}|${deviceId}`);
    if (!auth.ok || !auth.deviceId) {
      return jsonResp({ ok: false, error: auth.reason || "Device authorization required" }, auth.status);
    }
    if (auth.deviceId !== deviceId) {
      return jsonResp({ ok: false, error: "Authenticated device mismatch" }, 403);
    }

    const tokenId = crypto.randomUUID();
    const tokenSecret = randomHex(32); // 256-bit cryptographically secure random secret
    const tokenHash = await sha256Hex(tokenSecret);
    const tokenCiphertext = await encryptSecret(tokenSecret);

    const now = Date.now();
    const issuedAt = new Date(now).toISOString();
    const expiresAtMs = now + validityHours * 3600 * 1000;
    const expiresAt = new Date(expiresAtMs).toISOString();

    const { error: insertErr } = await supabase.from("ble_emergency_tokens").insert({
      token_id: tokenId,
      account_id: accountId,
      device_id: deviceId,
      token_hash: tokenHash,
      token_ciphertext: tokenCiphertext,
      issued_at: issuedAt,
      expires_at: expiresAt,
    });

    if (insertErr) {
      return jsonResp({ ok: false, error: `Failed to persist BLE token: ${insertErr.message}` }, 500);
    }

    return jsonResp({
      ok: true,
      tokenId,
      tokenSecret,
      issuedAt: now,
      expiresAt: expiresAtMs,
      accountId,
      deviceId,
    }, 200);
  }

  // -------------------------------------------------------------
  // Route 4: BLE Emergency Alert Relay Endpoint (Relay Phone -> Backend)
  // -------------------------------------------------------------
  if (req.method === "POST" && path.endsWith("/ble-relay-alert")) {
    const body = await req.json();
    const relayDeviceId = String(body.relayDeviceId ?? "ANONYMOUS_RELAY").trim();
    const packet = body.packet;

    if (!packet || typeof packet !== "object") {
      return jsonResp({ ok: false, error: "Missing or malformed emergency alert packet" }, 400);
    }

    const {
      protocolVersion,
      alertId,
      accountId,
      senderDeviceId,
      tokenId,
      issuedAt,
      expiresAt,
      nonce,
      alertType,
      latitude,
      longitude,
      accuracyMeters,
      emergencyMessage,
      signature,
    } = packet;

    // 1. Structure validation
    if (protocolVersion !== 1) {
      return jsonResp({ ok: false, error: `Unsupported protocol version: ${protocolVersion}` }, 400);
    }
    if (!alertId || !accountId || !senderDeviceId || !tokenId || !signature || !nonce) {
      return jsonResp({ ok: false, error: "Missing mandatory alert packet fields" }, 400);
    }

    // 2. Token / packet expiry checks
    const now = Date.now();
    if (expiresAt < now) {
      return jsonResp({ ok: false, error: "Emergency alert token has expired" }, 400);
    }
    if (Math.abs(now - issuedAt) > 7 * 24 * 3600 * 1000) {
      return jsonResp({ ok: false, error: "Alert issue timestamp exceeds validity window" }, 400);
    }

    // 3. Idempotency Check: Return existing acknowledgement if alertId was already successfully processed
    const { data: existingAlert } = await supabase
      .from("ble_emergency_alerts")
      .select("alert_id, status, created_at")
      .eq("alert_id", alertId)
      .maybeSingle();

    if (existingAlert) {
      return jsonResp({
        ok: true,
        alertId: existingAlert.alert_id,
        status: existingAlert.status,
        acknowledged: true,
        duplicate: true,
      }, 200);
    }

    // 4. Retrieve short-lived signing token from DB
    const { data: tokenRow, error: tokenErr } = await supabase
      .from("ble_emergency_tokens")
      .select("*")
      .eq("token_id", tokenId)
      .maybeSingle();

    if (tokenErr || !tokenRow) {
      return jsonResp({ ok: false, error: "Referenced BLE emergency token not found" }, 401);
    }
    if (tokenRow.revoked_at != null) {
      return jsonResp({ ok: false, error: "Token has been revoked" }, 401);
    }
    if (tokenRow.account_id !== accountId) {
      return jsonResp({ ok: false, error: "Token account mismatch" }, 403);
    }
    if (tokenRow.device_id !== senderDeviceId) {
      return jsonResp({ ok: false, error: "Token sender device mismatch" }, 403);
    }

    // 5. Decrypt token secret and verify cryptographic signature
    let tokenSecret: string;
    try {
      tokenSecret = await decryptSecret(tokenRow.token_ciphertext);
    } catch (e: unknown) {
      return jsonResp({ ok: false, error: `Failed to decrypt token key: ${(e as Error).message}` }, 500);
    }

    const canonicalString = buildBleCanonicalString(packet);
    const expectedSig = await hmacSha256Hex(tokenSecret, canonicalString);
    if (!timingSafeEqualHex(expectedSig, signature)) {
      return jsonResp({ ok: false, error: "Invalid BLE alert packet signature" }, 401);
    }

    // 6. Anti-replay Nonce Check (atomic insert into ble_alert_nonces)
    const { error: nonceErr } = await supabase.from("ble_alert_nonces").insert({
      account_id: accountId,
      nonce: String(nonce),
      device_id: senderDeviceId,
    });
    if (nonceErr) {
      return jsonResp({ ok: false, error: "Replay attack detected: nonce already consumed" }, 409);
    }

    // 7. Persist Alert in ble_emergency_alerts table
    const { error: alertInsertErr } = await supabase.from("ble_emergency_alerts").insert({
      alert_id: alertId,
      account_id: accountId,
      sender_device_id: senderDeviceId,
      relay_device_id: relayDeviceId,
      token_id: tokenId,
      protocol_version: protocolVersion,
      alert_type: alertType || "EMERGENCY",
      latitude: latitude != null ? Number(latitude) : null,
      longitude: longitude != null ? Number(longitude) : null,
      accuracy_meters: accuracyMeters != null ? Number(accuracyMeters) : null,
      emergency_message: emergencyMessage || "GuardianGrid BLE Emergency Alert",
      signature: signature,
      status: "RECEIVED",
      raw_packet: packet,
    });

    if (alertInsertErr) {
      // Handle potential race condition / concurrent duplicate
      if (alertInsertErr.code === "23505") { // unique constraint violation
        return jsonResp({
          ok: true,
          alertId,
          status: "RECEIVED",
          acknowledged: true,
          duplicate: true,
        }, 200);
      }
      return jsonResp({ ok: false, error: `Failed to record alert: ${alertInsertErr.message}` }, 500);
    }

    // Mark token consumed (if single-use policy or mark updated)
    await supabase.from("ble_emergency_tokens").update({
      consumed_at: new Date().toISOString(),
    }).eq("token_id", tokenId);

    // 8. Lookup Primary Emergency Contact for Account (Backend-only)
    const { data: contacts } = await supabase
      .from("guardian_emergency_contacts")
      .select("*")
      .eq("account_id", accountId)
      .order("is_primary", { ascending: false })
      .order("created_at", { ascending: true })
      .limit(1);

    const primaryContact = contacts?.[0];
    let smsDispatched = false;
    let smsError: string | null = null;
    let finalAlertStatus = "RECEIVED";

    if (primaryContact && primaryContact.phone_number) {
      const mapsLink = (latitude != null && longitude != null)
        ? `https://maps.google.com/?q=${latitude},${longitude}`
        : "UNAVAILABLE";
      const smsBody = `🚨 GUARDIANGRID ALERT: ${alertType || "EMERGENCY"} distress reported. Location: ${mapsLink}. Message: ${emergencyMessage || "I need immediate emergency assistance."}`;

      // 9. Dispatch SMS using Backend Provider
      const smsResult = await dispatchServerSideSms(primaryContact.phone_number, smsBody);

      // Record dispatch attempt
      await supabase.from("emergency_sms_dispatches").insert({
        alert_id: alertId,
        account_id: accountId,
        recipient_phone: primaryContact.phone_number,
        provider: smsResult.provider,
        provider_message_id: smsResult.providerMessageId || null,
        status: smsResult.success ? "SENT" : "FAILED",
        error_message: smsResult.errorMessage || null,
      });

      if (smsResult.success) {
        smsDispatched = true;
        finalAlertStatus = "SMS_SENT";
      } else {
        smsError = smsResult.errorMessage || "SMS dispatch failed";
        finalAlertStatus = "SMS_FAILED";
      }
    } else {
      smsError = "No registered emergency contact found for account";
      finalAlertStatus = "NO_CONTACT";
    }

    // Update alert status
    await supabase.from("ble_emergency_alerts").update({
      status: finalAlertStatus,
      acknowledged_at: new Date().toISOString(),
    }).eq("alert_id", alertId);

    // Also optional Telegram notification if configured
    if (TELEGRAM_BOT_TOKEN && DEFAULT_CHAT_ID) {
      const mapsLink = (latitude != null && longitude != null)
        ? `https://maps.google.com/?q=${latitude},${longitude}`
        : "UNAVAILABLE";
      const tgText = `🚨 GUARDIANGRID BLE RELAY EMERGENCY\nAlert: ${alertId}\nAccount: ${accountId}\nRelay Device: ${relayDeviceId}\nType: ${alertType}\nLocation: ${mapsLink}\nStatus: ${finalAlertStatus}`;
      fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ chat_id: DEFAULT_CHAT_ID, text: tgText }),
      }).catch(() => {});
    }

    return jsonResp({
      ok: true,
      alertId,
      status: finalAlertStatus,
      smsDispatched,
      smsError,
      acknowledged: true,
    }, 200);
  }

  // -------------------------------------------------------------
  // Route 5: Dispatch Direct Emergency Alert Envelope (Standard Flow)
  // -------------------------------------------------------------
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

  // -------------------------------------------------------------
  // Route 6: Upload Sequential Emergency Audio WAV Chunk
  // -------------------------------------------------------------
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

  // -------------------------------------------------------------
  // Route 7: Mark Incident Recording Completed
  // -------------------------------------------------------------
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

  // -------------------------------------------------------------
  // Route 8: Paired Guardian Authorized Audio/Alert Retrieval
  // -------------------------------------------------------------
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
          .createSignedUrl(ch.storage_object_path, 300);
        signedChunks.push({
          chunkSequence: ch.chunk_sequence,
          isPreTrigger: ch.is_pre_trigger,
          isFinal: ch.is_final,
          durationMs: ch.duration_ms,
          signedAudioUrl: signed?.signedUrl ?? null,
        });
      }
      alerts.push({ ...inc, chunks: signedChunks });
    }
    return jsonResp({ ok: true, alerts }, 200);
  }

  return jsonResp({ ok: false, error: "Unsupported route" }, 404);
});
