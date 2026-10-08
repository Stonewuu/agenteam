"use client";


import {useEffect, useRef} from "react";
import {Color, Mesh, Program, Renderer, Triangle} from "ogl";
import {TOOL_BORDER_GLOW_CONFIG, type ToolBorderGlowConfig} from "./conversation-tool-border-glow-config";
import styles from "./conversation-tool-border-glow.module.css";

const vertexShader = `
attribute vec2 uv;
attribute vec2 position;
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position, 0.0, 1.0);
}
`;

const fragmentShader = `
precision highp float;
varying vec2 vUv;
uniform vec2 uResolution;
uniform vec2 uCardSize;
uniform float uRadius;
uniform float uProgress;
uniform float uStaticTop;
uniform float uStartAtApproval;
uniform float uOpacity;
uniform float uCoreLight;
uniform float uGlowLength;
uniform float uGlowWidth;
uniform float uGlowIntensity;
uniform float uEdgeWidth;
uniform float uCenterFalloff;
uniform float uInnerGlowRatio;
uniform vec3 uPurple;
uniform vec3 uBlue;
uniform vec3 uPink;
uniform vec3 uGold;
const float PI = 3.14159265;

// 直边使用线段长度，圆角使用弧长，统一得到从左上角开始的顺时针距离。
float perimeterPosition(vec2 p, vec2 q, vec2 straight, float radius) {
  float horizontal = 2.0 * straight.x;
  float vertical = 2.0 * straight.y;
  float corner = PI * 0.5 * radius;
  if (q.x > 0.0 && q.y > 0.0) {
    float angle = atan(q.y, q.x);
    if (p.x >= 0.0 && p.y < 0.0) {
      return horizontal + (PI * 0.5 - angle) * radius;
    }
    if (p.x >= 0.0) {
      return horizontal + corner + vertical + angle * radius;
    }
    if (p.y >= 0.0) {
      return 2.0 * horizontal + 2.0 * corner + vertical + (PI * 0.5 - angle) * radius;
    }
    return 2.0 * horizontal + 3.0 * corner + 2.0 * vertical + angle * radius;
  }
  if (q.x > q.y) {
    if (p.x >= 0.0) {
      return horizontal + corner + p.y + straight.y;
    }
    return 2.0 * horizontal + 3.0 * corner + vertical + straight.y - p.y;
  }
  if (p.y < 0.0) {
    return p.x + straight.x;
  }
  return horizontal + 2.0 * corner + vertical + straight.x - p.x;
}

vec3 glowColor(float position) {
  float step = clamp(position, 0.0, 1.0) * 3.0;
  if (step < 1.0) {
    return mix(uPurple, uBlue, smoothstep(0.0, 1.0, step));
  }
  if (step < 2.0) {
    return mix(uBlue, uPink, smoothstep(0.0, 1.0, step - 1.0));
  }
  return mix(uPink, uGold, smoothstep(0.0, 1.0, step - 2.0));
}

void main() {
  vec2 p = vec2(vUv.x - 0.5, 0.5 - vUv.y) * uResolution;
  vec2 halfSize = uCardSize * 0.5;
  float radius = min(uRadius, min(halfSize.x, halfSize.y));
  vec2 straight = halfSize - radius;
  vec2 q = abs(p) - straight;
  float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  float perimeter = 4.0 * (straight.x + straight.y) + 2.0 * PI * radius;
  float position = perimeterPosition(p, q, straight, radius) / max(perimeter, 1.0);
  // 审批后的运动从同一个顶部位置开始，卡片尺寸变化时仍保持起点对齐。
  float topLength = max(2.0 * straight.x / max(perimeter, 1.0), 0.0001);
  float approvalPosition = topLength * 0.7;
  float progress = fract(uProgress + uStartAtApproval * approvalPosition);
  float phase = fract(position - progress + 0.5) - 0.5;

  // 顶部静态光带平滑展开为环绕光带，保留两端的连续淡出。
  float staticLength = min(uGlowLength, topLength);
  float staticStart = clamp(approvalPosition - staticLength * 0.5, 0.0, max(topLength - staticLength, 0.0));
  float staticHalfLength = phase < 0.0 ? approvalPosition - staticStart : staticStart + staticLength - approvalPosition;
  float halfGlowLength = mix(uGlowLength * 0.5, staticHalfLength, uStaticTop);
  float glowPosition = clamp(phase / max(halfGlowLength, 0.0001), -1.0, 1.0);
  float envelope = pow(max(0.0, 0.5 + 0.5 * cos(PI * glowPosition)), uCenterFalloff);
  float edge = exp(-pow(distance / uEdgeWidth, 2.0));
  float halo = (0.35 * exp(-pow(distance / (uGlowWidth * 8.0 / 19.0), 2.0))
    + 0.20 * exp(-pow(distance / uGlowWidth, 2.0))) * uGlowIntensity;
  halo *= distance < 0.0 ? uInnerGlowRatio : 1.0;
  float alpha = min(edge * 0.85 + halo, 1.0) * envelope * uOpacity;
  vec3 color = glowColor((glowPosition + 1.0) * 0.5);
  color = mix(color, vec3(1.0), uCoreLight * edge * envelope);
  gl_FragColor = vec4(color * alpha, alpha);
}
`;

/** 根据配置计算基础速度与短暂加速，始终保持位置连续。 */
function calculateGlowProgress(elapsedSeconds: number, config: ToolBorderGlowConfig) {
  const {lapSeconds, boostInterval, boostDuration, boostMultiplier} = config;
  if (boostInterval === 0 || boostDuration === 0 || boostMultiplier === 1) {
    return (elapsedSeconds / lapSeconds) % 1;
  }
  const boostElapsed = Math.max(0, elapsedSeconds - boostInterval);
  const completedBoosts = Math.floor(boostElapsed / boostInterval);
  const phase = Math.min((boostElapsed % boostInterval) / boostDuration, 1);
  // 累计加速带来的额外位移，保持每次加速前后的位置与速度连续。
  const boostIntegral = phase - Math.sin(2 * Math.PI * phase) / (2 * Math.PI);
  const extraSeconds = ((boostMultiplier - 1) * boostDuration * (completedBoosts + boostIntegral)) / 2;
  return ((elapsedSeconds + extraSeconds) / lapSeconds) % 1;
}

/** 颜色和亮度都按圆角边框的实际周长移动，不依赖中心角度或鼠标位置。 */
export function ConversationToolBorderGlow({toolCallId, config = TOOL_BORDER_GLOW_CONFIG, staticTop = false}: {
  toolCallId: string; config?: ToolBorderGlowConfig; staticTop?: boolean;
}) {
  const containerRef = useRef<HTMLSpanElement>(null);
  const staticTopRef = useRef(staticTop);
  const modeUpdaterRef = useRef<((fixed: boolean) => void) | null>(null);
  const approvalOriginRef = useRef({toolCallId, enabled: staticTop});

  useEffect(() => {
    staticTopRef.current = staticTop;
    modeUpdaterRef.current?.(staticTop);
  }, [staticTop]);

  useEffect(() => {
    const container = containerRef.current;
    const card = container?.parentElement;
    if (!container || !card) {
      return;
    }
    if (approvalOriginRef.current.toolCallId !== toolCallId) {
      approvalOriginRef.current = {toolCallId, enabled: staticTopRef.current};
    }
    let fixedAtTop = staticTopRef.current;
    let startAtApproval = approvalOriginRef.current.enabled || fixedAtTop;
    approvalOriginRef.current.enabled = startAtApproval;
    let leavingApproval = false;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const forcedColors = window.matchMedia("(forced-colors: active)");
    let renderer: Renderer | null = null;
    let geometry: Triangle | null = null;
    let program: Program | null = null;
    let mesh: Mesh | null = null;
    let frame: number | null = null;
    let visible = false;
    let failed = false;
    let elapsed = 0;
    let lastTime = 0;
    let lastPaint = 0;

    function stop() {
      if (frame !== null) {
        cancelAnimationFrame(frame);
        frame = null;
      }
      lastTime = 0;
    }

    function updateMotion() {
      if (!program) {
        return;
      }
      const transition = Math.min(elapsed / 0.24, 1);
      const staticAmount = fixedAtTop ? 1 : leavingApproval ? 1 - transition * transition * (3 - 2 * transition) : 0;
      program.uniforms.uProgress.value = fixedAtTop ? 0 : calculateGlowProgress(elapsed, config);
      program.uniforms.uStaticTop.value = staticAmount;
      program.uniforms.uStartAtApproval.value = startAtApproval ? 1 : 0;
    }

    function updateMode(fixed: boolean) {
      if (fixedAtTop === fixed) {
        return;
      }
      // 状态切换复用同一个画布，保留审批位置，并从基础速度开始接续运动。
      leavingApproval = fixedAtTop && !fixed;
      stop();
      fixedAtTop = fixed;
      startAtApproval = true;
      approvalOriginRef.current.enabled = true;
      elapsed = 0;
      lastPaint = 0;
      updateMotion();
      syncPlayback();
      if (renderer && mesh) {
        renderer.render({scene: mesh});
      }
    }

    function onContextLost(event: Event) {
      event.preventDefault();
      failed = true;
      stop();
      container!.removeAttribute("data-rendered");
    }

    function release() {
      stop();
      container!.removeAttribute("data-rendered");
      renderer?.gl.canvas.removeEventListener("webglcontextlost", onContextLost);
      geometry?.remove();
      program?.remove();
      renderer?.gl.canvas.remove();
      renderer?.gl.getExtension("WEBGL_lose_context")?.loseContext();
      renderer = null;
      geometry = null;
      program = null;
      mesh = null;
    }

    function updatePalette() {
      if (!program) {
        return;
      }
      const style = getComputedStyle(card!);
      const palette = [
        ["uPurple", "--tool-glow-purple", config.light.colors[0]],
        ["uBlue", "--tool-glow-blue", config.light.colors[1]],
        ["uPink", "--tool-glow-pink", config.light.colors[2]],
        ["uGold", "--tool-glow-gold", config.light.colors[3]],
      ];
      for (const [uniform, variable, fallback] of palette) {
        const color = new Color(style.getPropertyValue(variable).trim() || fallback);
        program.uniforms[uniform].value = [color.r, color.g, color.b];
      }
      const opacity = Number.parseFloat(style.getPropertyValue("--tool-glow-opacity"));
      const coreLight = Number.parseFloat(style.getPropertyValue("--tool-glow-core-light"));
      program.uniforms.uOpacity.value = Number.isFinite(opacity) ? opacity : config.light.opacity;
      program.uniforms.uCoreLight.value = Number.isFinite(coreLight) ? coreLight : config.light.coreLight;
      if (renderer && mesh) {
        renderer.render({scene: mesh});
      }
    }

    function resize() {
      if (!renderer || !program || !mesh) {
        return;
      }
      const width = Math.max(1, container!.clientWidth);
      const height = Math.max(1, container!.clientHeight);
      renderer.dpr = Math.min(window.devicePixelRatio || 1, 1.5, 1600 / width);
      renderer.setSize(width, height);
      program.uniforms.uResolution.value = [width, height];
      program.uniforms.uCardSize.value = [card!.clientWidth + 1, card!.clientHeight + 1];
      program.uniforms.uRadius.value = Math.max(0, (Number.parseFloat(getComputedStyle(card!).borderTopLeftRadius) || 12) - 0.5);
      renderer.render({scene: mesh});
    }

    function initialize() {
      if (renderer) {
        return true;
      }
      try {
        renderer = new Renderer({
          alpha: true,
          premultipliedAlpha: true,
          antialias: false,
          powerPreference: "low-power"
        });
        const gl = renderer.gl;
        gl.clearColor(0, 0, 0, 0);
        geometry = new Triangle(gl);
        program = new Program(gl, {
          vertex: vertexShader,
          fragment: fragmentShader,
          depthTest: false,
          depthWrite: false,
          uniforms: {
            uResolution: {value: [1, 1]},
            uCardSize: {value: [1, 1]},
            uRadius: {value: 12},
            uProgress: {value: calculateGlowProgress(elapsed, config)},
            uStaticTop: {value: fixedAtTop ? 1 : 0},
            uStartAtApproval: {value: startAtApproval ? 1 : 0},
            uOpacity: {value: config.light.opacity},
            uCoreLight: {value: config.light.coreLight},
            uGlowLength: {value: config.lengthRatio},
            uGlowWidth: {value: config.glowWidth},
            uGlowIntensity: {value: config.glowIntensity},
            uEdgeWidth: {value: config.edgeWidth},
            uCenterFalloff: {value: config.centerFalloff},
            uInnerGlowRatio: {value: config.innerGlowRatio},
            uPurple: {value: [1, 1, 1]},
            uBlue: {value: [1, 1, 1]},
            uPink: {value: [1, 1, 1]},
            uGold: {value: [1, 1, 1]},
          },
        });
        if (!gl.getProgramParameter(program.program, gl.LINK_STATUS)) {
          throw new Error("边框泛光的图形程序编译失败");
        }
        updateMotion();
        updatePalette();
        mesh = new Mesh(gl, {geometry, program});
        gl.canvas.setAttribute("aria-hidden", "true");
        gl.canvas.addEventListener("webglcontextlost", onContextLost);
        resize();
        container!.appendChild(gl.canvas);
        container!.setAttribute("data-rendered", "");
        return true;
      } catch (error) {
        console.error("工具边框泛光初始化失败，工具调用编号：", toolCallId, error);
        failed = true;
        release();
        return false;
      }
    }

    function draw(time: number) {
      if (!renderer || !program || !mesh) {
        return;
      }
      if (lastTime > 0) {
        elapsed += (time - lastTime) * 0.001;
      }
      lastTime = time;
      const frameInterval = 1000 / config.frameRate;
      if (time - lastPaint >= frameInterval) {
        updateMotion();
        renderer.render({scene: mesh});
        lastPaint = time - ((time - lastPaint) % frameInterval);
      }
      frame = requestAnimationFrame(draw);
    }

    function syncPlayback() {
      stop();
      if (forcedColors.matches || reducedMotion.matches && !fixedAtTop) {
        release();
        return;
      }
      if (visible && !document.hidden && !failed && initialize()) {
        // 顶部静态泛光只在初始化、尺寸或主题变化时绘制，不启动逐帧动画。
        if (!fixedAtTop) {
          frame = requestAnimationFrame(draw);
        }
      }
    }

    modeUpdaterRef.current = updateMode;
    const sizeObserver = new ResizeObserver(resize);
    const visibilityObserver = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      syncPlayback();
    });
    const themeObserver = new MutationObserver(updatePalette);
    sizeObserver.observe(container);
    visibilityObserver.observe(container);
    themeObserver.observe(document.documentElement, {attributes: true, attributeFilter: ["data-theme"]});
    document.addEventListener("visibilitychange", syncPlayback);
    reducedMotion.addEventListener("change", syncPlayback);
    forcedColors.addEventListener("change", syncPlayback);
    return () => {
      if (modeUpdaterRef.current === updateMode) {
        modeUpdaterRef.current = null;
      }
      sizeObserver.disconnect();
      visibilityObserver.disconnect();
      themeObserver.disconnect();
      document.removeEventListener("visibilitychange", syncPlayback);
      reducedMotion.removeEventListener("change", syncPlayback);
      forcedColors.removeEventListener("change", syncPlayback);
      release();
    };
  }, [toolCallId, config]);

  return <span ref={containerRef} className={styles.edgeLight} data-static-top={staticTop || undefined}
               aria-hidden="true"/>;
}
