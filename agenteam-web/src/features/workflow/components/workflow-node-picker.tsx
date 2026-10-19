"use client";

import {useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {IconSearch, IconX} from "@/components/ui/icons";
import {ContextMenuItem} from "@/components/ui/shadcn/context-menu";
import {useT} from "@/lib/i18n/locale-provider";
import {nodeNames} from "../lib/workflow-graph";
import {nodeChoices, workflowIcons, type AddableNode} from "./workflow-node-catalog";
import styles from "./workflow-node-picker.module.css";

export function WorkflowNodePicker({title, canAdd, onAdd, onClose, menu = false, footer}: {
  title: string;
  canAdd?: (type: AddableNode) => boolean;
  onAdd: (type: AddableNode) => void;
  onClose: () => void;
  menu?: boolean;
  footer?: ReactNode;
}) {
  const text = useT();
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const results = useRef<HTMLDivElement>(null);
  const footerElement = useRef<HTMLDivElement>(null);
  const hasFooter = Boolean(footer);
  const [query, setQuery] = useState("");
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  const choices = nodeChoices.filter(({type, description, group}) => {
    const searchable = [type, nodeNames[type], text(nodeNames[type]), description, text(description), group, text(group)]
      .join(" ").toLocaleLowerCase();
    return terms.every((term) => searchable.includes(term));
  });

  useEffect(() => {
    input.current?.focus({preventScroll: true});
  }, []);
  useEffect(() => {
    results.current?.scrollTo({top: 0});
  }, [query]);
  useLayoutEffect(() => {
    const element = footerElement.current;
    const container = root.current;
    if (!element || !container) {
      return;
    }
    const measure = () => container.style.setProperty("--node-picker-footer-height", `${element.offsetHeight}px`);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => {
      observer.disconnect();
      container.style.removeProperty("--node-picker-footer-height");
    };
  }, [hasFooter]);

  function navigate(event: KeyboardEvent<HTMLDivElement>) {
    const inSearch = event.target === input.current;
    if (menu && !inSearch) {
      return;
    }
    event.stopPropagation();
    if (event.nativeEvent.isComposing) {
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    const buttons = Array.from(root.current?.querySelectorAll<HTMLElement>("[data-node-choice]:not(:disabled):not([data-disabled])") ?? []);
    const index = buttons.indexOf(event.target as HTMLElement);
    if (event.key === "Enter" && inSearch && buttons[0]) {
      event.preventDefault();
      buttons[0].click();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next = index < 0 ? (event.key === "ArrowDown" ? 0 : buttons.length - 1)
        : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
      buttons[next]?.focus({preventScroll: true});
      buttons[next]?.scrollIntoView({block: "nearest"});
    } else if (!inSearch && (event.key === "Home" || event.key === "End")) {
      event.preventDefault();
      buttons[event.key === "Home" ? 0 : buttons.length - 1]?.focus();
    }
  }

  return <div ref={root} className={styles.picker}
    onKeyDown={navigate} onContextMenu={(event) => event.stopPropagation()}>
    <div className={styles.heading}><h2 id={`${id}-title`}>{title}</h2>
      <Button type="button" className={styles.iconButton} aria-label={text("取消添加节点")} onClick={onClose}><IconX size={16}/></Button>
    </div>
    <div className={styles.search}>
      <IconSearch size={17} aria-hidden/>
      <Input ref={input} className={styles.input} type="search" value={query}
        aria-label={text("搜索节点")} placeholder={text("搜索节点名称或功能")}
        aria-controls={`${id}-results`} autoComplete="off" onChange={(event) => setQuery(event.target.value)}/>
      {query && <Button type="button" className={`${styles.iconButton} ${styles.clear}`} aria-label={text("清空搜索")}
        onClick={() => {
          setQuery("");
          input.current?.focus();
        }}><IconX size={15}/></Button>}
    </div>
    <AnimatedHeight preserveControlShadows>
      <div ref={results} id={`${id}-results`} className={styles.results} aria-labelledby={`${id}-title`}>
        {["执行", "流程", "数据"].map((group) => {
          const items = choices.filter((choice) => choice.group === group);
          return items.length > 0 && <section key={group} className={styles.group} aria-label={text(group)}>
            <h3>{text(group)}</h3>
            {items.map(({type, description}) => {
              const Icon = workflowIcons[type];
              const content = <>
                <span className={styles.icon}><Icon size={19}/></span>
                <span className={styles.label}><strong>{text(nodeNames[type])}</strong><small>{text(description)}</small></span>
              </>;
              return menu ? <ContextMenuItem key={type} className={styles.item} data-node-choice={type}
                disabled={canAdd && !canAdd(type)} onClick={() => onAdd(type)}>{content}</ContextMenuItem>
                : <Button type="button" key={type} className={styles.item} data-node-choice={type}
                  disabled={canAdd && !canAdd(type)} onClick={() => onAdd(type)}>{content}</Button>;
            })}
          </section>;
        })}
        {choices.length === 0 && <p className={styles.empty} role="status">{text("没有匹配的节点")}</p>}
      </div>
    </AnimatedHeight>
    {footer && <div ref={footerElement} className={styles.footer}>{footer}</div>}
  </div>;
}
