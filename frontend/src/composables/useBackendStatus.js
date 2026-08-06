/**
 * useBackendStatus
 *
 * Polls GET /api/version and reports the backend connection state for StatusPill.
 *
 * The backend is a child process on an ephemeral port; if it dies, every action
 * fails while the UI looks fine. Polling is what makes that visible.
 *
 * Deliberate choices:
 * - Initial state is 'starting', never 'online'. Latent-Library's equivalent
 *   initialises to 'online' and so claims a connection before the first check runs.
 * - The interval and any in-flight request are torn down on unmount.
 * - A non-200 (including 401) means reachable-but-not-usable, so it maps to
 *   'starting'. Only a thrown request means 'offline'.
 * - No flap debounce: a failed request to 127.0.0.1 means the process is gone.
 */
import { ref, watch, onUnmounted } from 'vue';

const POLL_INTERVAL_MS = 10_000;
const REQUEST_TIMEOUT_MS = 5_000;

export function useBackendStatus(apiBase, apiToken, isBackendReady) {
  const status = ref('starting');
  let timer = null;
  let controller = null;

  const setStatus = (next) => {
    if (status.value === next) return;          // log transitions only, never per tick
    console.log(`[LMO] Backend ${status.value} -> ${next}`);
    status.value = next;
  };

  const check = async () => {
    if (controller) controller.abort();          // never let ticks stack
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetch(`${apiBase.value}/api/version`, {
        headers: { Authorization: `Bearer ${apiToken.value}` },
        signal: controller.signal
      });
      setStatus(res.ok ? 'online' : 'starting');
    } catch {
      setStatus('offline');
    } finally {
      clearTimeout(timeout);
    }
  };

  const stop = () => {
    if (timer) { clearInterval(timer); timer = null; }
    if (controller) { controller.abort(); controller = null; }
  };

  // Nothing to poll until the IPC handshake has produced a port and token.
  watch(isBackendReady, (ready) => {
    if (!ready) return;
    if (!apiToken.value) {
      setStatus('offline');   // fatal engine start, or a plain browser with no electronAPI
      return;
    }
    check();
    timer = setInterval(check, POLL_INTERVAL_MS);
  }, { immediate: true });

  onUnmounted(stop);

  return { status };
}
