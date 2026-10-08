"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type ComponentProps, memo, useMemo} from "react";
import {Block, type BlockProps, type ControlsConfig, type ExtraProps, type IconMap, Streamdown} from "streamdown";
import {cjk} from "@streamdown/cjk";
import {code} from "@streamdown/code";
import {IconCheck, IconCopy} from "./icons";
import {compactAnimationNodes} from "./markdown-animation-nodes";

const plugins = {code, cjk};
const controls: ControlsConfig = {code: {copy: true, download: false}, table: false, mermaid: false};
const icons: Partial<IconMap> = {
  CopyIcon: ({size = 14}) => <IconCopy size={size}/>,
  CheckIcon: ({size = 14}) => <IconCheck size={size}/>
};
const labels = {
  copyCode: "复制代码",
  copied: "已复制",
  imageNotAvailable: "图片暂时无法显示",
  downloadImage: "下载图片"
};
// 按字符识别中文新增部分，再合并同批元素；收到文字即显示，不等待前一批动画。
const animation = {
  animation: "fadeIn" as const,
  duration: 180,
  easing: "ease-out",
  sep: "char" as const,
  stagger: 0,
  maxBacklogMs: 0
};
const linkSafety = {enabled: false};

function AnimatedTextSpan({node, ...props}: ComponentProps<"span"> & ExtraProps) {
  // 新文字可能复用同一个位置；用起点更新内部元素，确保每批新文字都有淡入。
  return <span {...props} key={String(node?.properties["data-stream-animation-start"] ?? "")}/>;
}

const components: ComponentProps<typeof Streamdown>["components"] = {span: AnimatedTextSpan};
const absoluteLinks: ComponentProps<typeof Streamdown>["components"] = {
  ...components,
  a: ({children, href, title}) => href ?
    <a data-streamdown="link" href={href} title={title} target="_blank" rel="noopener noreferrer">{children}</a> :
    <span>{children}</span>,
};

const AnimatedMarkdownBlock = memo(function AnimatedMarkdownBlock(props: BlockProps) {
  const rehypePlugins = useMemo(() => props.animatePlugin
    ? [...(props.rehypePlugins ?? []), compactAnimationNodes] : props.rehypePlugins, [props.animatePlugin, props.rehypePlugins]);
  return <Block {...props} rehypePlugins={rehypePlugins}/>;
});

export const MarkdownContent = memo(function MarkdownContent({
                                                               content,
                                                               className,
                                                               animate = false,
                                                               mode = "streaming",
                                                               allowImages = true,
                                                               allowRelativeLinks = true
                                                             }: {
  content: string;
  className?: string;
  animate?: boolean;
  mode?: "streaming" | "static";
  allowImages?: boolean;
  allowRelativeLinks?: boolean;
}) {
  const uiText = useT();
  return <Streamdown className={className} mode={mode} controls={controls} icons={icons}
                     translations={localizeCatalog(labels, uiText)} isAnimating={animate}
                     animated={animation} BlockComponent={AnimatedMarkdownBlock} plugins={plugins}
                     components={allowRelativeLinks ? components : absoluteLinks} skipHtml linkSafety={linkSafety}
                     urlTransform={(url, key) => {
                       try {
                         if (key === "src") {
                           return allowImages && url.startsWith("/api/v1/") && !url.startsWith("//") ? url : "";
                         }
                         const parsed = allowRelativeLinks ? new URL(url, "https://local.invalid") : new URL(url);
                         return ["https:", "http:", "mailto:"].includes(parsed.protocol) ? url : "";
                       } catch {
                         return "";
                       }
                     }}>{content}</Streamdown>;
});
