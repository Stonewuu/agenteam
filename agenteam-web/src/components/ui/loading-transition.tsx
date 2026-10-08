"use client";

import {
  createContext,
  type HTMLAttributes,
  type ReactNode,
  useCallback,
  useContext,
  useLayoutEffect,
  useRef
} from "react";
import {observeLoadingSize} from "./loading-size-transition";
import styles from "./loading-transition.module.css";

const LoadingSurfaceContext = createContext(false);

/** 弹层统一测量完整外框，内部查询不重复播放尺寸动画。 */
export function LoadingSurface({children}: { children: ReactNode }) {
  return <LoadingSurfaceContext.Provider value>{children}</LoadingSurfaceContext.Provider>;
}

export function useLoadingSurface<T extends HTMLElement = HTMLDivElement>() {
  const transition = useRef<ReturnType<typeof observeLoadingSize> | null>(null);
  return useCallback((element: T | null) => {
    transition.current?.disconnect();
    transition.current = null;
    if (element) {
      const observer = observeLoadingSize(element, element, {includeNestedLoading: true, fadeContent: false});
      transition.current = observer;
      observer.update();
      return () => {
        observer.disconnect();
        if (transition.current === observer) {
          transition.current = null;
        }
      };
    }
  }, []);
}

/** 加载前后保留同一个容器，按真实宽高过渡，不拉伸文字或裁切控件阴影。 */
export function LoadingTransition({
                                    children,
                                    state,
                                    layout = "flow",
                                    className = "",
                                    contentClassName = "",
                                    ...props
                                  }: HTMLAttributes<HTMLDivElement> & {
  state?: string;
  layout?: "flow" | "stack";
  contentClassName?: string;
}) {
  const outer = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const transition = useRef<ReturnType<typeof observeLoadingSize> | null>(null);
  const surfaceManaged = useContext(LoadingSurfaceContext);

  useLayoutEffect(() => {
    if (!outer.current || !inner.current || surfaceManaged) {
      return;
    }
    const observer = observeLoadingSize(outer.current, inner.current);
    transition.current = observer;
    return () => {
      observer.disconnect();
      transition.current = null;
    };
  }, [surfaceManaged]);

  useLayoutEffect(() => {
    transition.current?.update(state);
  });

  return <div {...props} ref={outer} className={`${styles.frame} ${className}`} data-loading-transition
              data-content-layout={layout}>
    <div ref={inner} className={`${styles.content} ${contentClassName}`} data-loading-content>{children}</div>
  </div>;
}
