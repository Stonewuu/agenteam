"use client";

import {useEffect, useRef} from "react";
import {fragmentShader, vertexShader} from "./light-rays-shaders";

// 光束着色器来自 React Bits；此处增加按需加载、绘制上限、暂停与资源释放。
export function LightRays({paused}: { paused: boolean }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const pausedRef = useRef(paused);
  const syncRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    pausedRef.current = paused;
    syncRef.current?.();
  }, [paused]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    let disposed = false;
    let cleanup: (() => void) | undefined;

    async function initialize() {
      const {Renderer, Program, Triangle, Mesh} = await import("ogl");
      if (disposed || !container) {
        return;
      }
      const renderer = new Renderer({
        dpr: 1,
        alpha: true,
        antialias: false,
        powerPreference: "low-power",
      });
      const gl = renderer.gl;
      container.appendChild(gl.canvas);
      const uniforms = {
        iTime: {value: 14},
        iResolution: {value: [1, 1]},
        rayPos: {value: [0, 0]},
        rayDir: {value: [-0.36, 1]},
        raysColor: {value: [2.8, 1.74, 0.9]},
        raysSpeed: {value: 0.65},
        lightSpread: {value: 0.8},
        rayLength: {value: 3.5},
        pulsating: {value: 0},
        fadeDistance: {value: 1.6},
        saturation: {value: 0.9},
        mousePos: {value: [0.5, 0.5]},
        mouseInfluence: {value: 0},
        noiseAmount: {value: 0.015},
        distortion: {value: 0.025},
        lightMode: {value: 0},
      };
      const geometry = new Triangle(gl);
      const program = new Program(gl, {
        vertex: vertexShader,
        fragment: fragmentShader,
        uniforms,
      });
      const mesh = new Mesh(gl, {geometry, program});
      const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
      const smallScreen = window.matchMedia("(max-width: 850px)");
      let frame = 0;
      let lastDraw = 0;
      let inView = true;
      let contextLost = false;

      const render = () => {
        if (!disposed && !contextLost) {
          renderer.render({scene: mesh});
        }
      };
      const tick = (time: number) => {
        frame = requestAnimationFrame(tick);
        // 每秒最多绘制 30 帧，动画不触发 React 组件更新。
        if (time - lastDraw < 1000 / 30) {
          return;
        }
        uniforms.iTime.value += Math.min((time - lastDraw) / 1000, 0.08);
        lastDraw = time;
        render();
      };
      const sync = () => {
        cancelAnimationFrame(frame);
        if (disposed || contextLost) {
          return;
        }
        const hidden = document.hidden || !inView;
        const stopped =
          pausedRef.current || reduced.matches || smallScreen.matches;
        container.dataset.motion = hidden
          ? "hidden"
          : stopped
            ? "paused"
            : "playing";
        if (hidden) {
          return;
        }
        render();
        if (!stopped) {
          lastDraw = performance.now();
          frame = requestAnimationFrame(tick);
        }
      };
      const resize = () => {
        const {clientWidth: width, clientHeight: height} = container;
        if (!width || !height) {
          return;
        }
        // 背景无需屏幕原生精度，最长边不超过 960 像素。
        renderer.dpr = Math.min(1, 960 / Math.max(width, height));
        renderer.setSize(width, height);
        uniforms.iResolution.value = [gl.canvas.width, gl.canvas.height];
        uniforms.rayPos.value = [
          gl.canvas.width * 0.78,
          -gl.canvas.height * 0.2,
        ];
        sync();
      };
      const onContextLost = () => {
        contextLost = true;
        cancelAnimationFrame(frame);
      };
      const resizeObserver = new ResizeObserver(resize);
      const intersectionObserver = new IntersectionObserver(([entry]) => {
        inView = entry.isIntersecting;
        sync();
      });
      resizeObserver.observe(container);
      intersectionObserver.observe(container);
      document.addEventListener("visibilitychange", sync);
      reduced.addEventListener("change", sync);
      smallScreen.addEventListener("change", sync);
      gl.canvas.addEventListener("webglcontextlost", onContextLost);
      syncRef.current = sync;
      resize();

      cleanup = () => {
        cancelAnimationFrame(frame);
        resizeObserver.disconnect();
        intersectionObserver.disconnect();
        document.removeEventListener("visibilitychange", sync);
        reduced.removeEventListener("change", sync);
        smallScreen.removeEventListener("change", sync);
        gl.canvas.removeEventListener("webglcontextlost", onContextLost);
        geometry.remove();
        program.remove();
        gl.getExtension("WEBGL_lose_context")?.loseContext();
        gl.canvas.remove();
        syncRef.current = null;
      };
    }

    void initialize().catch((error) => {
      console.error("初始化身份页背景动效失败", error);
      // 不支持图形加速时保留完整表单和静态品牌区域。
      container.dataset.motion = "unavailable";
    });
    return () => {
      disposed = true;
      cleanup?.();
    };
  }, []);

  return <div className="auth-rays" ref={containerRef} aria-hidden="true"/>;
}
