import type {EditionNavigationExtension} from "./types/navigation-extension";

/** 静态入口与组件注册分开，服务端页面可以安全读取导航。 */
export const editionNavigationExtension: EditionNavigationExtension = {management: [], platform: []};
