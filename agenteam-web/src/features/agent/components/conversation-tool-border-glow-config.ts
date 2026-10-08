import type {CSSProperties} from "react";

/** 全局默认配置。日常调效果只需修改此对象；单个工具块可通过 glowConfig 覆盖其中的参数。 */
export const TOOL_BORDER_GLOW_CONFIG: ToolBorderGlowConfig = {
  // 光带沿边框的长度比例，范围 0.01～1；1 / 3 表示覆盖三分之一周长。
  lengthRatio: 1 / 2,
  // 泛光向边框内外扩散的宽度，单位为像素；越大，外围光晕越宽。
  glowWidth: 15,
  // 泛光强度，1 为当前效果，0 关闭泛光但保留亮线。
  glowIntensity: 1,
  // 亮线的扩散宽度，单位为像素。
  edgeWidth: 0.5,
  // 中心亮度的集中程度；1 为当前效果，越大越集中，越小越均匀。
  centerFalloff: 0.8,
  // 内侧泛光相对外侧的强度，范围 0～1；0 表示只向外发光。
  innerGlowRatio: 0.2,

  // 基础速度：不加速时转完一圈的秒数。
  lapSeconds: 4,
  // 两次加速开始之间的间隔，单位为秒；0 表示关闭加速。
  boostInterval: 2,
  // 每次加速持续的秒数；不能超过间隔，超过时按间隔处理。
  boostDuration: 0.8,
  // 加速的峰值倍数；1 表示不加速。前后仍会平滑恢复基础速度。
  boostMultiplier: 6,
  // 每秒最多绘制的帧数，范围 1～120；提高后更流畅，也会增加绘制开销。
  frameRate: 30,

  light: {
    // 动态光带的整体透明度，范围 0～1。
    opacity: 0.7,
    // 中心亮线混入白色的程度，范围 0～1。
    coreLight: 0,
    // 静态彩色边框的透明度，范围 0～1。
    borderOpacity: 0.5,
    // 沿光带依次渐变的四种颜色，使用六位十六进制颜色值。
    colors: ["#9873d5", "#409ed0", "#d975ac", "#d3a05f"],
  },
  // 深色主题使用相同的参数含义，可单独调整。
  dark: {
    opacity: 0.9,
    coreLight: 0.5,
    borderOpacity: 0.55,
    colors: ["#be8cf4", "#58c9ed", "#f28dc5", "#f3cc91"],
  },
};

export type ToolBorderGlowTheme = {
  opacity: number;
  coreLight: number;
  borderOpacity: number;
  colors: readonly [string, string, string, string];
};

export type ToolBorderGlowConfig = {
  lengthRatio: number;
  glowWidth: number;
  glowIntensity: number;
  edgeWidth: number;
  centerFalloff: number;
  innerGlowRatio: number;
  lapSeconds: number;
  boostInterval: number;
  boostDuration: number;
  boostMultiplier: number;
  frameRate: number;
  light: ToolBorderGlowTheme;
  dark: ToolBorderGlowTheme;
};

export type ToolBorderGlowOptions = Partial<Omit<ToolBorderGlowConfig, "light" | "dark">> & {
  light?: Partial<ToolBorderGlowTheme>;
  dark?: Partial<ToolBorderGlowTheme>;
};

function clampConfigNumber(value: number, fallback: number, min: number, max = Infinity) {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : fallback));
}

function resolveThemeConfig(defaults: ToolBorderGlowTheme, options?: Partial<ToolBorderGlowTheme>): ToolBorderGlowTheme {
  const theme = {...defaults, ...options};
  const color = (index: number) => {
    const value = theme.colors?.[index];
    return typeof value === "string" && /^#[\da-f]{6}$/i.test(value.trim()) ? value.trim() : defaults.colors[index];
  };
  return {
    opacity: clampConfigNumber(theme.opacity, defaults.opacity, 0, 1),
    coreLight: clampConfigNumber(theme.coreLight, defaults.coreLight, 0, 1),
    borderOpacity: clampConfigNumber(theme.borderOpacity, defaults.borderOpacity, 0, 1),
    colors: [color(0), color(1), color(2), color(3)],
  };
}

/** 限制会造成除零或无效图形计算的数值，保证调整参数时仍能继续显示。 */
export function resolveToolBorderGlowConfig(options: ToolBorderGlowOptions = {}): ToolBorderGlowConfig {
  const defaults = TOOL_BORDER_GLOW_CONFIG;
  const config = {...defaults, ...options};
  const boostInterval = clampConfigNumber(config.boostInterval, 2, 0);
  return {
    lengthRatio: clampConfigNumber(config.lengthRatio, 1 / 3, 0.01, 1),
    glowWidth: clampConfigNumber(config.glowWidth, 19, 0.1),
    glowIntensity: clampConfigNumber(config.glowIntensity, 1, 0),
    edgeWidth: clampConfigNumber(config.edgeWidth, 0.7, 0.1),
    centerFalloff: clampConfigNumber(config.centerFalloff, 1, 0.1),
    innerGlowRatio: clampConfigNumber(config.innerGlowRatio, 0.7, 0, 1),
    lapSeconds: clampConfigNumber(config.lapSeconds, 4, 0.1),
    boostInterval,
    boostDuration: clampConfigNumber(config.boostDuration, 0.8, 0, boostInterval),
    boostMultiplier: clampConfigNumber(config.boostMultiplier, 8, 1),
    frameRate: clampConfigNumber(config.frameRate, 30, 1, 120),
    light: resolveThemeConfig(defaults.light, options.light),
    dark: resolveThemeConfig(defaults.dark, options.dark),
  };
}

/** 同一份配置同时驱动画布、彩色边框和静态光晕。 */
export function createToolBorderGlowStyle(config: ToolBorderGlowConfig): CSSProperties {
  const variables: Record<string, string | number> = {
    "--tool-glow-padding": `${Math.ceil(Math.max((config.glowWidth / 19) * 24, config.edgeWidth * 3))}px`,
    "--tool-glow-scale": config.glowWidth / 19,
    "--tool-glow-intensity": config.glowIntensity,
    "--tool-glow-edge-width": `${config.edgeWidth}px`,
  };
  for (const mode of ["light", "dark"] as const) {
    const theme = config[mode];
    variables[`--tool-${mode}-opacity`] = theme.opacity;
    variables[`--tool-${mode}-core-light`] = theme.coreLight;
    variables[`--tool-${mode}-border-opacity`] = theme.borderOpacity;
    variables[`--tool-${mode}-purple`] = theme.colors[0];
    variables[`--tool-${mode}-blue`] = theme.colors[1];
    variables[`--tool-${mode}-pink`] = theme.colors[2];
    variables[`--tool-${mode}-gold`] = theme.colors[3];
  }
  return variables as CSSProperties;
}
