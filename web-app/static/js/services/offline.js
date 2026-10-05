// Offline support: registers the service worker and saves audio into its cache.
//
// The app shell and Qur'an data are cached by the service worker as they load. Audio is only
// saved when asked ("Save for offline"), like the Android app — a revision list can be large.

export const AUDIO_CACHE = 'la-audio-v1';

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || location.protocol === 'file:') return null;
  try {
    return await navigator.serviceWorker.register('sw.js');
  } catch {
    return null;
  }
}

const offlineCapable = () => 'caches' in globalThis;

export async function countCached(urls) {
  if (!offlineCapable()) return 0;
  const cache = await caches.open(AUDIO_CACHE);
  let n = 0;
  await Promise.all(
    urls.map(async (url) => {
      if (await cache.match(url)) n++;
    }),
  );
  return n;
}

/**
 * Downloads `urls` into the audio cache, a few at a time, skipping what is already there.
 * Files are only stored when complete (a failed or partial download is never kept).
 */
export async function saveForOffline(urls, { onProgress = () => {}, signal } = {}) {
  if (!offlineCapable()) throw new Error('This browser cannot store audio for offline use.');
  const cache = await caches.open(AUDIO_CACHE);
  let done = 0;
  let failed = 0;
  const queue = [...urls];
  const worker = async () => {
    while (queue.length) {
      if (signal?.aborted) return;
      const url = queue.shift();
      try {
        if (!(await cache.match(url))) {
          const res = await fetch(url, { signal });
          if (!res.ok) throw new Error(String(res.status));
          await cache.put(url, res);
        }
      } catch {
        failed++;
      }
      done++;
      onProgress(done, urls.length, failed);
    }
  };
  await Promise.all(Array.from({ length: 4 }, worker));
  return { done, failed };
}

export async function clearOfflineAudio() {
  if (offlineCapable()) await caches.delete(AUDIO_CACHE);
}
