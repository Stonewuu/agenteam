#!/usr/bin/env node
"use strict";

const {chromium} = require("playwright");

/** 每次读取使用独立浏览器会话，正文直接返回，不在项目目录生成结果或登录资料。 */
function options(argv) {
  if (argv[0] !== "read" || !argv[1]) {
    throw new Error("用法：agenteam-browser read 网址 [--start 0] [--max-chars 24000] [--timeout-ms 30000] [--wait-ms 1000] [--wait-for 选择器]");
  }
  let url;
  try {
    url = new URL(argv[1]);
  } catch {
    throw new Error("网址格式不正确，请提供完整的 HTTP 或 HTTPS 地址。");
  }
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("仅支持不含账号和密码的 HTTP 或 HTTPS 网页地址。");
  }
  const result = {url: url.href, start: 0, maxChars: 24000, timeout: 30000, wait: 1000, selector: null};
  const numeric = new Map([
    ["--start", ["start", 0, 10000000]],
    ["--max-chars", ["maxChars", 100, 100000]],
    ["--timeout-ms", ["timeout", 1000, 120000]],
    ["--wait-ms", ["wait", 0, 10000]],
  ]);
  for (let index = 2; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (name === "--wait-for" && value && value.length <= 512) {
      result.selector = value;
    } else if (numeric.has(name) && /^\d+$/.test(value ?? "")) {
      const [key, min, max] = numeric.get(name);
      const number = Number(value);
      if (!Number.isSafeInteger(number) || number < min || number > max) {
        throw new Error("读取范围或等待时间超过允许值。");
      }
      result[key] = number;
    } else {
      throw new Error("存在未知参数或缺少参数值，请查看命令用法。");
    }
  }
  return result;
}

async function readPage(settings) {
  const deadline = Date.now() + settings.timeout;
  const browser = await chromium.launch({headless: true, timeout: settings.timeout,
    args: ["--block-new-web-contents"], ignoreDefaultArgs: ["--disable-popup-blocking"]});
  let timer;
  try {
    return await Promise.race([
      (async () => {
        const context = await browser.newContext({acceptDownloads: false, serviceWorkers: "block"});
        const page = await context.newPage();
        context.on("page", (extra) => {
          if (extra !== page) {
            void extra.close().catch((error) => {
              reportFailure("关闭额外网页失败", error);
            });
          }
        });
        page.setDefaultTimeout(Math.max(1, deadline - Date.now()));
        await context.route("**/*", async (route) => {
          const request = route.request();
          if (!["http:", "https:"].includes(new URL(request.url()).protocol)
            || ["image", "media", "font"].includes(request.resourceType())) {
            await route.abort();
          } else {
            await route.continue();
          }
        });
        const response = await page.goto(settings.url, {waitUntil: "domcontentloaded"});
        const status = response?.status() ?? 0;
        if (status >= 400) {
          return {isError: true, code: "WEB_REQUEST_REJECTED", status,
            content: "网站拒绝或未能完成读取；请使用其他公开来源，或在获得访问资格后重试。"};
        }
        if (settings.selector) {
          await page.locator(settings.selector).first().waitFor({state: "visible"});
        }
        if (settings.wait > 0) {
          await page.waitForTimeout(settings.wait);
        }
        const result = await page.evaluate(({start, maxChars}) => {
          const root = document.querySelector("main, article, [role=main]") ?? document.body;
          const text = (root?.innerText ?? "").replace(/\n{3,}/g, "\n\n").trim();
          // 按 Unicode 字符切片，避免续读从中文或表情的中间开始。
          // 只保存本段字符，避免为完整长文额外建立一个占用大量内存的字符数组。
          const characters = [];
          let totalCharacters = 0;
          for (const character of text) {
            if (totalCharacters >= start && characters.length < maxChars) {
              characters.push(character);
            }
            totalCharacters += 1;
          }
          const end = Math.min(start + maxChars, totalCharacters);
          const links = [];
          const seen = new Set();
          for (const link of (root ?? document).querySelectorAll("a[href]")) {
            if (links.length >= 40) {
              break;
            }
            if (link.innerText.trim() && /^https?:/.test(link.href) && link.href.length <= 2048 && !seen.has(link.href)) {
              seen.add(link.href);
              links.push({title: link.innerText.trim().slice(0, 200), url: link.href});
            }
          }
          return {title: document.title, url: location.href, content: characters.join(""),
            start, totalCharacters, truncated: end < totalCharacters,
            nextStart: end < totalCharacters ? end : null, links};
        }, settings);
        return {...result, status, isError: !result.content,
          ...(result.content ? {} : {code: "WEB_CONTENT_EMPTY", content: "当前读取范围没有正文，可调整起始位置或等待页面中的目标元素。"})};
      })(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("WEB_READ_TIMEOUT")), Math.max(1, deadline - Date.now()));
      }),
    ]);
  } finally {
    clearTimeout(timer);
    await browser.close();
  }
}

function reportFailure(reason, error) {
  const frames = (error.stack ?? "").split("\n").filter((line) => /^\s+at /.test(line))
    .map((line) => line.replace(/https?:\/\/[^\s)]+/g, "[地址已隐藏]"));
  process.stderr.write(`${reason}\n${frames.join("\n")}\n`);
}

async function main() {
  let settings;
  try {
    settings = options(process.argv.slice(2));
  } catch (error) {
    process.stdout.write(JSON.stringify({isError: true, code: "WEB_ARGUMENTS_INVALID", content: error.message}) + "\n");
    process.exitCode = 2;
    return;
  }
  try {
    const result = await readPage(settings);
    process.stdout.write(JSON.stringify(result) + "\n");
    process.exitCode = result.isError ? 1 : 0;
  } catch (error) {
    // 浏览器异常可能包含完整网址或页面内容，公共结果只返回明确的错误类别。
    const timeout = error.name === "TimeoutError" || error.message === "WEB_READ_TIMEOUT";
    const reason = timeout ? "网页读取超时" : error.message.match(/net::ERR_[A-Z_]+|ENOENT|Executable doesn't exist/)?.[0]
      ?? "浏览器无法完成请求";
    reportFailure(reason, error);
    process.stdout.write(JSON.stringify({isError: true, code: timeout ? "WEB_READ_TIMEOUT" : "WEB_BROWSER_FAILED",
      content: timeout ? "网页读取超时，可缩小等待范围或使用其他来源继续。"
        : "浏览器未能完成读取，请检查沙盒联网配置、浏览器安装和目标地址。"}) + "\n");
    process.exitCode = 1;
  }
}

if (require.main === module) {
  void main();
}

module.exports = {options, readPage};
