"use client";

import {Tabs as TabsPrimitive} from "@base-ui/react/tabs";
import {Tabs as TabsRoot, TabsList, TabsTrigger} from "./shadcn/tabs";
import {type ReactNode, useEffect, useRef, useSyncExternalStore} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import type {IconUser} from "./icons";

const narrowQuery = "(max-width: 800px)";
const readNarrow = () => window.matchMedia(narrowQuery).matches;
const readServerNarrow = () => false;

function subscribeNarrow(listener: () => void) {
  const media = window.matchMedia(narrowQuery);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

export function Tabs<T extends string>({
                                         value,
                                         onChange,
                                         items,
                                         orientation = "horizontal",
                                         variant = "default",
                                         label,
                                       }: {
  value: T;
  onChange: (value: T) => void;
  items: {
    value: T;
    label: string;
    count?: number;
    icon?: typeof IconUser;
    prefix?: ReactNode;
  }[];
  orientation?: "horizontal" | "vertical";
  variant?: "default" | "navigation" | "filter";
  label?: string;
}) {
  const t = useT();
  const list = useRef<HTMLDivElement>(null);
  const narrow = useSyncExternalStore(
    subscribeNarrow,
    readNarrow,
    readServerNarrow,
  );
  const direction =
    variant === "navigation" && narrow ? "horizontal" : orientation;
  useEffect(() => {
    if (variant === "navigation" && direction === "horizontal") {
      const selected = list.current?.querySelector('[aria-selected="true"]');
      let scrollable: HTMLElement | null = list.current;
      while (scrollable && scrollable.scrollWidth <= scrollable.clientWidth) {
        scrollable = scrollable.parentElement;
      }
      if (selected && scrollable) {
        const bounds = scrollable.getBoundingClientRect();
        const item = selected.getBoundingClientRect();
        if (item.left < bounds.left) {
          scrollable.scrollLeft += item.left - bounds.left;
        } else if (item.right > bounds.right) {
          scrollable.scrollLeft += item.right - bounds.right;
        }
      }
    }
  }, [value, variant, direction, t.locale]);
  return (
    <TabsRoot
      value={value}
      onValueChange={(next) => onChange(next as T)}
      orientation={direction}
      className="agenteam-tabs-root"
    >
      <TabsList
        ref={list}
        aria-label={label ?? t("内容分类")}
        activateOnFocus
        className={`tabs ${variant === "navigation" ? "tabs-navigation" : variant === "filter" ? "tabs-filter" : ""}`}
      >
        <TabsPrimitive.Indicator className="tabs-indicator"/>
        {items.map(({icon: Icon, ...item}) => (
          <TabsTrigger
            key={item.value}
            value={item.value}
            className="agenteam-tab-trigger"
          >
            {Icon && <Icon size={17}/>}
            {item.prefix && <span className="tab-prefix">{item.prefix}</span>}
            {item.label}
            {item.count !== undefined && (
              <span className="tab-count">{item.count}</span>
            )}
          </TabsTrigger>
        ))}
      </TabsList>
    </TabsRoot>
  );
}
