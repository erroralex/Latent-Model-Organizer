/**
 * useTheme
 * 
 * Manages application design system theme settings. Standardized on Latent Design System.
 */
import { ref } from 'vue';

const availableThemes = ['latent'];
const currentTheme = ref('latent');

export function useTheme() {
  const applyTheme = (themeName = 'latent') => {
    currentTheme.value = 'latent';
    document.body.classList.add('theme-latent');
  };

  return {
    currentTheme,
    availableThemes,
    applyTheme
  };
}
