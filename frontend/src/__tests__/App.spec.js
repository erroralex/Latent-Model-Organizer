import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import App from '../App.vue';

// Proves the assembled wiring: useBackendStatus -> :status="backendStatus" -> StatusPill.
// StatusPill defaults its `status` prop to 'starting', so a renamed prop or a
// dropped binding here would still render a plausible pill and stay green in
// StatusPill's and useBackendStatus's own isolated specs.
describe('App integration: backend status wiring', () => {
  beforeEach(() => {
    window.electronAPI = {
      getBackendPort: vi.fn().mockResolvedValue({ port: 9999, token: 'test-token' })
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
  });

  afterEach(() => {
    delete window.electronAPI;
    vi.unstubAllGlobals();
  });

  it('renders "Backend: Online" once the IPC handshake and first poll resolve', async () => {
    const wrapper = mount(App, {
      global: {
        // Heavy/unrelated subtrees stubbed out; StatusPill is left real since
        // it is the seam under test.
        stubs: {
          Sidebar: true,
          SorterView: true,
          FetcherView: true,
          SummaryModal: true,
          SettingsModal: true,
          ConsoleWindow: true
        }
      }
    });

    // initializeBackendConnection() awaits electronAPI.getBackendPort(), then
    // useBackendStatus's watcher fires check(), which awaits fetch().
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('Backend: Online');

    wrapper.unmount();
  });
});
