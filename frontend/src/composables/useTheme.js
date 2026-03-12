/**
 * A Vue composable for dynamic runtime theme management.
 *
 * This service leverages Vite's glob import feature to discover and load CSS
 * theme files from the filesystem at runtime. It provides a mechanism to
 * inject raw CSS strings directly into the document head and synchronize
 * the application's visual state with the user's preferences.
 *
 * Core features:
 * - Dynamic Injection: Updates a dedicated <style> element with the selected theme's CSS variables.
 * - Persistence: Synchronizes the selected theme with localStorage for session-to-session consistency.
 * - Class Management: Toggles theme-specific classes (e.g., .theme-neon) on the body element to support scope-based CSS overrides.
 * - Auto-Discovery: Automatically populates the list of available themes based on the contents of the assets/css/themes directory.
 */

import { ref } from 'vue';

const themeFiles = import.meta.glob('../assets/css/themes/*.css', { 
  query: '?raw',
  import: 'default'
});

const availableThemes = Object.keys(themeFiles).map(path => {
  const fileName = path.split('/').pop();
  return fileName.replace('.css', '');
});

const currentTheme = ref(localStorage.getItem('lmo-theme') || 'neon');

export function useTheme() {

  const applyTheme = async (themeName) => {
    const themePath = Object.keys(themeFiles).find(path => path.includes(`/${themeName}.css`));

    if (!themePath) {
      console.error(`Theme not found: ${themeName}`);
      return;
    }

    try {
      const rawCss = await themeFiles[themePath]();

      let styleEl = document.getElementById('dynamic-theme');
      if (!styleEl) {
        styleEl = document.createElement('style');
        styleEl.id = 'dynamic-theme';
        document.head.appendChild(styleEl);
      }
      styleEl.innerHTML = rawCss;

      document.body.classList.forEach(cls => {
        if (cls.startsWith('theme-')) {
          document.body.classList.remove(cls);
        }
      });
      document.body.classList.add(`theme-${themeName}`);

      currentTheme.value = themeName;
      localStorage.setItem('lmo-theme', themeName);
      
      console.log(`Applied theme: ${themeName}`);

    } catch (error) {
      console.error(`Failed to apply theme: ${themeName}`, error);
    }
  };

  applyTheme(currentTheme.value);

  return {
    currentTheme,
    availableThemes,
    applyTheme
  };
}
