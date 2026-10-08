import type {EditionClientExtension} from "./types/client-extension";

/** 组合构建允许商业仓库替换此注册入口，公共组件不引用私有源码。 */
export const editionClientExtension: EditionClientExtension = {
  edition: "community",
  managementPanels: {},
};
