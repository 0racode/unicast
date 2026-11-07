// Supabase Edge Function: livekit-token
//
// Hands out LiveKit access tokens.
//   - role "broadcaster": only a signed-in user (you, the admin) gets one. Can speak.
//   - role "listener":    anyone gets one. Can listen only, never speak.
//
// Secrets (set in Supabase > Edge Functions > Secrets, never in the app):
//   LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET
// SUPABASE_URL and SUPABASE_ANON_KEY are provided automatically.

const LIVEKIT_URL = Deno.env.get("LIVEKIT_URL") ?? "";
const API_KEY = Deno.env.get("LIVEKIT_API_KEY") ?? "";
const API_SECRET = Deno.env.get("LIVEKIT_API_SECRET") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

const ROOM = "unicast-live";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
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

async function signJwt(payload: Record<string, unknown>): Promise<string> {
  const header = base64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = base64Url(JSON.stringify(payload));
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(API_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`${header}.${body}`),
  );
  return `${header}.${body}.${base64Url(signature)}`;
}

function makeToken(identity: string, canPublish: boolean, ttlSeconds: number) {
  const now = Math.floor(Date.now() / 1000);
  return signJwt({
    iss: API_KEY,
    sub: identity,
    nbf: now,
    exp: now + ttlSeconds,
    video: {
      room: ROOM,
      roomJoin: true,
      canPublish,
      canPublishData: false,
      canSubscribe: true,
    },
  });
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

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Use POST." }, 405);

  if (!LIVEKIT_URL || !API_KEY || !API_SECRET) {
    return json({ error: "The server is not set up yet. Missing LiveKit secrets." }, 500);
  }

  let role = "listener";
  try {
    const body = await req.json();
    if (body?.role === "broadcaster") role = "broadcaster";
  } catch (_) {
    // No body means a listener.
  }

  if (role === "broadcaster") {
    if (!(await isSignedInUser(req.headers.get("Authorization")))) {
      return json({ error: "Only the admin can broadcast." }, 403);
    }
    const token = await makeToken("broadcaster", true, 6 * 3600);
    return json({ token, url: LIVEKIT_URL, room: ROOM });
  }

  const token = await makeToken(`listener-${crypto.randomUUID()}`, false, 4 * 3600);
  return json({ token, url: LIVEKIT_URL, room: ROOM });
});
