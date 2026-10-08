import "server-only";

import {cache} from "react";
import {cookies, headers} from "next/headers";
import {detectLocale, isLocale, localeCookie} from "./locales";

/** 在当前请求内读取语言，避免不同访问者共享可变的服务端状态。 */
export const requestLocale = cache(async () => {
  const selected = (await cookies()).get(localeCookie)?.value;
  return isLocale(selected) ? selected : detectLocale((await headers()).get("accept-language"));
});
