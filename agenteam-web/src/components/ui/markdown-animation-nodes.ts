import type {Element, Root, RootContent, Text} from "hast";

/** 合并同时淡入的文字，旧文字恢复为文本，避免长段落保留逐字元素。 */
export function compactAnimationNodes() {
  return (tree: Root) => {
    compactChildren(tree);
  };
}

function compactChildren(parent: Root | Element) {
  const children: RootContent[] = [];
  let offset = 0;
  for (const child of parent.children) {
    const animation = animationText(child);
    if (animation) {
      const {text, style} = animation;
      if (/(?:^|;)\s*--sd-duration:\s*0(?:ms|s)(?:;|$)/.test(style)) {
        appendText(children, text);
      } else {
        const previous = children.at(-1);
        if (previous?.type === "element" && animationText(previous)?.style === style) {
          (previous.children[0] as Text).value += text;
        } else {
          animation.element.properties["data-stream-animation-start"] = offset;
          children.push(child);
        }
      }
      offset += text.length;
      continue;
    }
    if (child.type === "text") {
      appendText(children, child.value);
      offset += child.value.length;
    } else {
      if (child.type === "element") {
        compactChildren(child);
      }
      children.push(child);
      // 格式节点也占一个位置，区分链接前后的新增文字。
      offset++;
    }
  }
  parent.children = children as Element["children"];
}

function appendText(children: RootContent[], value: string) {
  const previous = children.at(-1);
  if (previous?.type === "text") {
    previous.value += value;
  } else {
    children.push({type: "text", value});
  }
}

function animationText(node: RootContent): { element: Element; text: string; style: string } | null {
  if (node.type !== "element" || node.tagName !== "span" || node.properties["data-sd-animate"] !== true
    || typeof node.properties.style !== "string" || node.children.length !== 1 || node.children[0].type !== "text"
    || Object.keys(node.properties).some((key) => !["data-sd-animate", "style", "data-stream-animation-start"].includes(key))) {
    return null;
  }
  return {element: node, text: node.children[0].value, style: node.properties.style};
}
