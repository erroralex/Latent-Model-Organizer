/**
 * <h1>useTheme</h1>
 * <p>
 * A Vue composable for high-performance, dynamic runtime theme management.
 * This service leverages Vite's static asset discovery to load and inject
 * CSS variables based on user selection.
 * </p>
 *
 * <h2>Theme Injection Pipeline</h2>
 * <ul>
 *   <li><b>Discovery:</b> Automatically scans the {@code assets/css/themes} directory to build the available theme registry.</li>
 *   <li><b>Dynamic Injection:</b> Asynchronously fetches and injects the selected theme's raw CSS into a dedicated {@code <style>} element in the document head.</li>
 *   <li><b>Class Synchronization:</b> Manages the {@code .theme-*} class on the body element to allow for scoped component overrides.</li>
 *   <li><b>Persistence:</b> Synchronizes the selected theme with {@code localStorage} for session consistency.</li>
 * </ul>
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
