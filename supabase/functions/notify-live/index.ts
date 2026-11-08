// Supabase Edge Function: notify-live
//
// Sends the "Unicast is live" push notification to every phone subscribed to the "live" topic.
//   - Only the signed-in admin can trigger it.
//   - Only works while the live flag is on.
//   - Will not send again within 10 minutes, so going live twice does not spam listeners.
//
// Secret to add in Supabase > Edge Functions > Secrets:
//   FIREBASE_SERVICE_ACCOUNT  = the full contents of the Firebase service account JSON file
// These are provided by Supabase automatically:
//   SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "";

const REPEAT_GUARD_MS = 10 * 60 * 1000;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function base64Url(input: ArrayBuffer | string): string {
  const bytes = typeof input === "string"
    ? new TextEncoder().encode(input)
    : new Uint8Array(input);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\\n/g, "")
    .replace(/\s/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

// Exchanges the Firebase service account for a short-lived Google access token.
async function googleAccessToken(account: { client_email: string; private_key: string }) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  const assertion = `${header}.${claims}.${base64Url(signature)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const data = await res.json();
  if (!res.ok || !data.access_token) {
    throw new Error(`Google sign-in failed: ${data.error_description ?? data.error ?? res.status}`);
  }
  return data.access_token as string;
}

// Asks Supabase whether the caller is a real signed-in user (not just the public anon key).
async function isSignedInUser(authHeader: string | null): Promise<boolean> {
  if (!authHeader) return false;
  const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: ANON_KEY, Authorization: authHeader },
  });
  if (!res.ok) return false;
  const user = await res.json();
  return Boolean(user?.id) && user?.role === "authenticated";
}

const dbHeaders = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST." }, 405);

  if (!(await isSignedInUser(req.headers.get("Authorization")))) {
    return json({ error: "Only the admin can send this." }, 403);
  }

  if (!ACCOUNT_JSON) {
    return json({ error: "Notifications are not set up yet. Missing FIREBASE_SERVICE_ACCOUNT." }, 500);
  }

  let account: { client_email: string; private_key: string; project_id: string };
  try {
    account = JSON.parse(ACCOUNT_JSON);
  } catch (_) {
    return json({ error: "FIREBASE_SERVICE_ACCOUNT is not valid JSON." }, 500);
  }

  try {
    // Only while live, and not again within 10 minutes.
    const statusRes = await fetch(
      `${SUPABASE_URL}/rest/v1/live_status?select=is_live,last_notified_at&id=eq.1`,
      { headers: dbHeaders },
    );
    const rows = await statusRes.json();
    const status = Array.isArray(rows) ? rows[0] : null;
    if (!status?.is_live) return json({ sent: false, reason: "Not live." });
    if (
      status.last_notified_at &&
      Date.now() - Date.parse(status.last_notified_at) < REPEAT_GUARD_MS
    ) {
      return json({ sent: false, reason: "Already notified recently." });
    }

    const accessToken = await googleAccessToken(account);
    const push = await fetch(
      `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            topic: "live",
            notification: {
              title: "Unicast is live",
              body: "A live show has started. Tap to listen.",
            },
            android: {
              priority: "HIGH",
              ttl: "300s",
              notification: { channel_id: "live" },
            },
          },
        }),
      },
    );
    const result = await push.json();
    if (!push.ok) {
      return json({ error: `Push failed: ${result?.error?.message ?? push.status}` }, 502);
    }

    await fetch(`${SUPABASE_URL}/rest/v1/live_status?id=eq.1`, {
      method: "PATCH",
      headers: { ...dbHeaders, Prefer: "return=minimal" },
      body: JSON.stringify({ last_notified_at: new Date().toISOString() }),
    });

    return json({ sent: true });
  } catch (e) {
    return json({ error: String(e instanceof Error ? e.message : e) }, 500);
  }
});
