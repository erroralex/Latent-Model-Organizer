/**
 * useUiZoom
 *
 * Ctrl/Cmd + Mouse Wheel scales the whole renderer's Chromium zoom factor
 * (webFrame, exposed via window.windowAPI in electron/preload.js), matching
 * the shortcut Latent Tools already ships. Ctrl/Cmd + 0 resets to 100%.
 */
import { onMounted, onUnmounted } from 'vue';

const ZOOM_STEP = 0.05;
const ZOOM_MIN = 0.5;
const ZOOM_MAX = 2.5;

export function useUiZoom() {
  const handleWheel = (e) => {
    if (!(e.ctrlKey || e.metaKey)) return;
    const api = window.windowAPI;
    if (!api?.getZoomFactor || !api?.setZoomFactor) return;

    e.preventDefault();
    const currentZoom = api.getZoomFactor();
    const delta = e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP;
    const newZoom = Math.min(Math.max(ZOOM_MIN, Math.round((currentZoom + delta) * 100) / 100), ZOOM_MAX);
    api.setZoomFactor(newZoom);
  };

  const handleKeydown = (e) => {
    if (!(e.ctrlKey || e.metaKey) || e.key !== '0') return;
    if (document.activeElement?.tagName === 'TEXTAREA' || document.activeElement?.tagName === 'INPUT') return;

    e.preventDefault();
    window.windowAPI?.setZoomFactor?.(1.0);
  };

  onMounted(() => {
    window.addEventListener('wheel', handleWheel, { passive: false });
    window.addEventListener('keydown', handleKeydown);
  });

  onUnmounted(() => {
    window.removeEventListener('wheel', handleWheel);
    window.removeEventListener('keydown', handleKeydown);
  });
}
