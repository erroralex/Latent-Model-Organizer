import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ref, nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { useBackendStatus } from '../useBackendStatus';

// Mount inside a component so onUnmounted is registered against a real instance.
function harness(apiBase, apiToken, isBackendReady) {
  let exposed;
  const wrapper = mount({
    template: '<div />',
    setup() {
      exposed = useBackendStatus(apiBase, apiToken, isBackendReady);
      return {};
    }
  });
  return { wrapper, get status() { return exposed.status; } };
}

describe('useBackendStatus', () => {
  let apiBase, apiToken, isBackendReady;

  beforeEach(() => {
    vi.useFakeTimers();
    apiBase = ref('http://127.0.0.1:5000');
    apiToken = ref('tok');
    isBackendReady = ref(false);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('starts in starting before the handshake completes', () => {
    vi.stubGlobal('fetch', vi.fn());
    const h = harness(apiBase, apiToken, isBackendReady);
    expect(h.status.value).toBe('starting');
  });

  it('goes online when /api/version returns 200', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('online');
  });

  it('sends the bearer token', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:5000/api/version',
      expect.objectContaining({ headers: { Authorization: 'Bearer tok' } })
    );
  });

  it('treats a non-200 as starting, not offline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('starting');
  });

  it('goes offline when the request throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('offline');
  });

  it('goes offline when the handshake yields no token (fatal or browser dev mode)', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    apiToken.value = '';
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    expect(h.status.value).toBe('offline');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('detects a backend that dies after startup', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true })
      .mockRejectedValue(new Error('ECONNREFUSED'));
    vi.stubGlobal('fetch', fetchMock);
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.status.value).toBe('online');

    await vi.advanceTimersByTimeAsync(10000);
    expect(h.status.value).toBe('offline');
  });

  it('stops polling once unmounted', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    const h = harness(apiBase, apiToken, isBackendReady);
    isBackendReady.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(0);
    const callsBefore = fetchMock.mock.calls.length;

    h.wrapper.unmount();
    await vi.advanceTimersByTimeAsync(30000);
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });
});
