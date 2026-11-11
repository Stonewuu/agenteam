export type ThemePreference = "light" | "dark" | "system";
export const themeStorageKey = "agenteam:theme";
let stopThemeWatcher: (() => void) | undefined;

export function currentTheme(): ThemePreference {
  const value = document.documentElement.dataset.themePreference;
  return value === "light" || value === "dark" ? value : "system";
}

export function subscribeTheme(callback: () => void) {
  window.addEventListener("agenteam:theme-changed", callback);
  return () => window.removeEventListener("agenteam:theme-changed", callback);
}

/** 立即应用主题，只保存在当前浏览器。 */
export function applyTheme(theme: ThemePreference) {
  stopThemeWatcher?.();
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const update = () => {
    const dark = theme === "dark" || (theme === "system" && media.matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.dataset.themePreference = theme;
    document.documentElement.classList.toggle("dark", dark);
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
    window.dispatchEvent(new Event("agenteam:theme-changed"));
  };
  try {
    localStorage.setItem(themeStorageKey, theme);
  } catch (failure) {
    console.warn("Failed to persist the local theme preference; the current selection remains active.", failure);
  }
  update();
  if (theme === "system") {
    media.addEventListener("change", update);
  }
  stopThemeWatcher = () => media.removeEventListener("change", update);
}
