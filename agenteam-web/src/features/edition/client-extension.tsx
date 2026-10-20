import type {EditionClientExtension} from "./types/client-extension";

/** 页面扩展的注册入口，业务组件通过此入口读取可用扩展。 */
export const editionClientExtension: EditionClientExtension = {
  edition: "community",
  managementPanels: {},
};
