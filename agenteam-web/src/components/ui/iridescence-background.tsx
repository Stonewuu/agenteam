"use client";

import {useEffect, useRef} from "react";
import {Mesh, Program, Renderer, Triangle} from "ogl";
import styles from "./iridescence-background.module.css";

// 图形计算来自 React Bits 的 Iridescence，许可见同目录 iridescence-background.LICENSE。
// https://reactbits.dev/backgrounds/iridescence
const vertexShader = `
attribute vec2 uv;
attribute vec2 position;
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position, 0, 1);
}
`;

const fragmentShader = `
precision highp float;
uniform float uTime;
uniform vec3 uColor;
uniform vec3 uResolution;
uniform vec2 uMouse;
uniform float uAmplitude;
uniform float uSpeed;
varying vec2 vUv;
void main() {
  float mr = min(uResolution.x, uResolution.y);
  vec2 uv = (vUv.xy * 2.0 - 1.0) * uResolution.xy / mr;
  uv += (uMouse - vec2(0.5)) * uAmplitude;
  float d = -uTime * 0.5 * uSpeed;
  float a = 0.0;
  for (float i = 0.0; i < 8.0; ++i) {
    a += cos(i - d - a * uv.x);
    d += sin(uv.y * i + a);
  }
  d += uTime * 0.5 * uSpeed;
  vec3 col = vec3(cos(uv * vec2(d, a)) * 0.6 + 0.4, cos(a + d) * 0.5 + 0.5);
  col = cos(col * cos(vec3(d, a, 2.5)) * 0.5 + 0.5) * uColor;
  gl_FragColor = vec4(col, 1.0);
}
`;

/** 仅在可见时绘制；收起或收到正文后，由调用方卸载并释放图形资源。 */
export function IridescenceBackground() {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const forcedColors = window.matchMedia("(forced-colors: active)");
    let renderer: Renderer | null = null;
    let geometry: Triangle | null = null;
    let program: Program | null = null;
    let mesh: Mesh | null = null;
    let frame: number | null = null;
    let visible = false;
    let failed = false;
    let lastPaint = 0;

    function stop() {
      if (frame !== null) {
        cancelAnimationFrame(frame);
        frame = null;
      }
    }

    function release() {
      stop();
      geometry?.remove();
      program?.remove();
      renderer?.gl.canvas.remove();
      renderer?.gl.getExtension("WEBGL_lose_context")?.loseContext();
      renderer = null;
      geometry = null;
      program = null;
      mesh = null;
    }

    function resize() {
      if (!renderer || !program) {
        return;
      }
      const width = Math.max(1, container!.clientWidth);
      const height = Math.max(1, container!.clientHeight);
      renderer.dpr = Math.min(1, 960 / width);
      renderer.setSize(width, height);
      // 狭长区域沿用宽幅背景的曲线尺度，避免挤成密集条纹。
      program.uniforms.uResolution.value = [Math.min(width, height * 2.5), height, 1];
      // 画布改变尺寸会清空上一帧，立即重绘，避免拖动宽度时闪出空白。
      if (mesh) {
        renderer.render({scene: mesh});
      }
    }

    function initialize() {
      if (renderer) {
        return true;
      }
      try {
        renderer = new Renderer({dpr: 1, alpha: true, antialias: false, powerPreference: "low-power"});
        const gl = renderer.gl;
        geometry = new Triangle(gl);
        program = new Program(gl, {
          vertex: vertexShader,
          fragment: fragmentShader,
          uniforms: {
            uTime: {value: 0},
            uColor: {value: [1, 0.94, 0.88]},
            uResolution: {value: [1, 1, 1]},
            uMouse: {value: [0.5, 0.5]},
            uAmplitude: {value: 0},
            uSpeed: {value: 0.18},
          },
        });
        mesh = new Mesh(gl, {geometry, program});
        gl.canvas.setAttribute("aria-hidden", "true");
        program.uniforms.uTime.value = performance.now() * 0.001;
        resize();
        container!.appendChild(gl.canvas);
        return true;
      } catch (error) {
        console.error("虹彩背景初始化失败", error);
        failed = true;
        release();
        return false;
      }
    }

    function draw(time: number) {
      if (!renderer || !program || !mesh) {
        return;
      }
      if (time - lastPaint >= 1000 / 30) {
        program.uniforms.uTime.value = time * 0.001;
        renderer.render({scene: mesh});
        lastPaint = time;
      }
      frame = requestAnimationFrame(draw);
    }

    function syncPlayback() {
      stop();
      if (visible && !document.hidden && !reducedMotion.matches && !forcedColors.matches && !failed && initialize()) {
        frame = requestAnimationFrame(draw);
      }
    }

    const sizeObserver = new ResizeObserver(resize);
    const visibilityObserver = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      syncPlayback();
    });
    sizeObserver.observe(container);
    visibilityObserver.observe(container);
    document.addEventListener("visibilitychange", syncPlayback);
    reducedMotion.addEventListener("change", syncPlayback);
    forcedColors.addEventListener("change", syncPlayback);
    return () => {
      sizeObserver.disconnect();
      visibilityObserver.disconnect();
      document.removeEventListener("visibilitychange", syncPlayback);
      reducedMotion.removeEventListener("change", syncPlayback);
      forcedColors.removeEventListener("change", syncPlayback);
      release();
    };
  }, []);

  return <div ref={containerRef} className={styles.background} aria-hidden="true"/>;
}
