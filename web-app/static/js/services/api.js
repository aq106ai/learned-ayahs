// JSON API client for server.py. Mutations carry the CSRF header the server requires.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export async function api(method, path, body) {
  const headers = { 'X-Requested-With': 'learned-ayahs' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(`api/${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'offline', 'Could not reach the server.');
  }
  let data = null;
  try {
    data = await res.json();
  } catch {
    // Non-JSON (e.g. a static host with no API): treated as "no server".
  }
  if (!res.ok || data === null) {
    throw new ApiError(res.status, data?.error ?? 'error', data?.message ?? `Request failed (${res.status}).`);
  }
  return data;
}

const SERVER_KEY = 'la:v1:server';

/**
 * Whether this page is served by server.py (accounts, sync, audio cache) or a static host.
 * A server that answered before is remembered, so starting offline still uses the account's
 * local copy and the audio saved for offline (reachable: false until it answers again).
 */
export async function probeServer() {
  try {
    const health = await api('GET', 'health');
    try {
      localStorage.setItem(SERVER_KEY, JSON.stringify(health));
    } catch {
      // storage unavailable: nothing to remember
    }
    return { available: true, reachable: true, ...health };
  } catch (err) {
    if (err.status === 0) {
      let saved = null;
      try {
        saved = JSON.parse(localStorage.getItem(SERVER_KEY) ?? 'null');
      } catch {
        saved = null;
      }
      if (saved) return { ...saved, available: true, reachable: false };
    }
    return { available: false, reachable: false };
  }
}
