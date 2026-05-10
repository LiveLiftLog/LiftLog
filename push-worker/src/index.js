// SPLITSTAK push scheduling worker.
//
// Routes:
//   POST /schedule    { subscription, events: [{ fireAt }] }
//                     Replaces any pending events for this subscription
//                     and arms a Durable Object alarm for the soonest
//                     fireAt timestamp.
//   POST /unschedule  { endpoint }
//                     Cancels all pending events for this subscription.
//
// Each `event` becomes one empty Web Push delivery to the subscription
// endpoint at the requested wall-clock time. The page side schedules a
// burst of events to signal phase by chime count: 2 events 300 ms apart
// for the 10-second warning, 3 events 300 ms apart for the finish. The
// service worker shows one notification per push (replacing previous via
// the same `tag`), so the user sees one card on the lock screen but
// hears 1, 2, or 3 chimes depending on phase.
//
// Storage model: one Durable Object per subscription endpoint
// (`idFromName(endpoint)`). The DO holds the subscription identity and
// the sorted list of remaining events. `alarm()` fires due events,
// re-arms for the next, or clears state when none remain.

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), 'Content-Type': 'application/json' },
  });
}

function textResponse(body, status = 200) {
  return new Response(body, { status, headers: corsHeaders() });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
      return textResponse('', 204);
    }

    if (url.pathname === '/' && request.method === 'GET') {
      return textResponse('splitstak-push ok', 200);
    }

    if (url.pathname === '/schedule' && request.method === 'POST') {
      const body = await request.json();
      if (!body || !body.subscription || !body.subscription.endpoint || !Array.isArray(body.events)) {
        return jsonResponse({ error: 'bad request' }, 400);
      }
      const id = env.PUSH_TIMER.idFromName(body.subscription.endpoint);
      const stub = env.PUSH_TIMER.get(id);
      const r = await stub.fetch('https://internal/schedule', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return new Response(await r.text(), { status: r.status, headers: corsHeaders() });
    }

    if (url.pathname === '/unschedule' && request.method === 'POST') {
      const body = await request.json();
      if (!body || !body.endpoint) {
        return jsonResponse({ error: 'bad request' }, 400);
      }
      const id = env.PUSH_TIMER.idFromName(body.endpoint);
      const stub = env.PUSH_TIMER.get(id);
      const r = await stub.fetch('https://internal/unschedule', { method: 'POST' });
      return new Response(await r.text(), { status: r.status, headers: corsHeaders() });
    }

    if (url.pathname === '/complete' && request.method === 'POST') {
      const body = await request.json();
      if (!body || !body.endpoint || !body.kind) {
        return jsonResponse({ error: 'bad request' }, 400);
      }
      const id = env.PUSH_TIMER.idFromName(body.endpoint);
      const stub = env.PUSH_TIMER.get(id);
      const r = await stub.fetch('https://internal/complete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind: body.kind }),
      });
      return new Response(await r.text(), { status: r.status, headers: corsHeaders() });
    }

    return textResponse('not found', 404);
  },
};

export class PushTimer {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === '/schedule') {
      const body = await request.json();
      const events = body.events
        .filter((e) => typeof e.fireAt === 'number')
        .sort((a, b) => a.fireAt - b.fireAt);
      await this.state.storage.put('subscription', body.subscription);
      await this.state.storage.put('events', events);
      if (events.length > 0) {
        await this.state.storage.setAlarm(events[0].fireAt);
      } else {
        await this.state.storage.deleteAlarm();
      }
      return new Response('ok');
    }

    if (url.pathname === '/unschedule') {
      await this.state.storage.deleteAll();
      await this.state.storage.deleteAlarm();
      return new Response('ok');
    }

    if (url.pathname === '/complete') {
      // Page reports it played the local Web Audio for this milestone, so
      // we can drop the corresponding pending push events. Called from the
      // page's setTimeout the moment the local beep fires; a 600 ms offset
      // on the server-side alarm gives this request time to land before
      // the alarm dispatches.
      const body = await request.json();
      const events = (await this.state.storage.get('events')) || [];
      const remaining = events.filter((e) => e.kind !== body.kind);
      if (remaining.length === 0) {
        await this.state.storage.delete('events');
        await this.state.storage.deleteAlarm();
      } else {
        await this.state.storage.put('events', remaining);
        await this.state.storage.setAlarm(remaining[0].fireAt);
      }
      return new Response('ok');
    }

    return new Response('not found', { status: 404 });
  }

  async alarm() {
    const subscription = await this.state.storage.get('subscription');
    const events = (await this.state.storage.get('events')) || [];
    if (!subscription || events.length === 0) {
      await this.state.storage.deleteAlarm();
      return;
    }

    // Anything within the next 50 ms counts as "due now" — fire it without
    // setting another alarm just to wait an imperceptible window.
    const now = Date.now();
    const due = events.filter((e) => e.fireAt <= now + 50);
    const remaining = events.filter((e) => e.fireAt > now + 50);

    for (let i = 0; i < due.length; i++) {
      try {
        await sendEmptyPush(subscription, this.env);
      } catch (err) {
        // Subscription invalid / expired — bail out, drop pending events.
        if (err && err.status && (err.status === 404 || err.status === 410)) {
          await this.state.storage.deleteAll();
          await this.state.storage.deleteAlarm();
          return;
        }
        // Other transient errors: best-effort, keep going.
      }
    }

    if (remaining.length > 0) {
      await this.state.storage.put('events', remaining);
      await this.state.storage.setAlarm(remaining[0].fireAt);
    } else {
      await this.state.storage.delete('events');
      await this.state.storage.deleteAlarm();
    }
  }
}

// ----- Web Push (VAPID, no payload) -----

async function sendEmptyPush(subscription, env) {
  const url = new URL(subscription.endpoint);
  const audience = url.origin;
  const jwt = await makeVapidJwt(audience, env.VAPID_SUBJECT, env.VAPID_PUBLIC_KEY, env.VAPID_PRIVATE_KEY);
  const t0 = Date.now();
  console.log(`[push] -> ${url.host} at ${new Date(t0).toISOString()}`);
  const response = await fetch(subscription.endpoint, {
    method: 'POST',
    headers: {
      'TTL': '60',
      'Urgency': 'high',
      'Authorization': `vapid t=${jwt}, k=${env.VAPID_PUBLIC_KEY}`,
      'Content-Length': '0',
    },
  });
  console.log(`[push] <- ${url.host} status=${response.status} elapsed=${Date.now() - t0}ms`);
  if (!response.ok) {
    const err = new Error(`push send ${response.status}`);
    err.status = response.status;
    throw err;
  }
}

async function makeVapidJwt(audience, subject, publicKeyB64, privateKeyB64) {
  const header = b64urlJson({ alg: 'ES256', typ: 'JWT' });
  const payload = b64urlJson({
    aud: audience,
    exp: Math.floor(Date.now() / 1000) + 12 * 3600,
    sub: subject,
  });
  const signingInput = `${header}.${payload}`;

  const privateKeyBytes = b64urlDecode(privateKeyB64);
  const publicKeyBytes = b64urlDecode(publicKeyB64);
  const jwk = {
    kty: 'EC',
    crv: 'P-256',
    d: b64urlEncode(privateKeyBytes),
    x: b64urlEncode(publicKeyBytes.slice(1, 33)),
    y: b64urlEncode(publicKeyBytes.slice(33, 65)),
    ext: true,
  };
  const key = await crypto.subtle.importKey(
    'jwk',
    jwk,
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['sign'],
  );
  const sigBuf = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${b64urlEncode(new Uint8Array(sigBuf))}`;
}

function b64urlEncode(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlJson(obj) {
  return b64urlEncode(new TextEncoder().encode(JSON.stringify(obj)));
}

function b64urlDecode(str) {
  let s = str.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}
