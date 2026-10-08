# README 图片素材 / README visual assets

中英文 README 分别使用六张 1920×1080 图片。英文版使用英文界面，中文与英文对话图均收起历史列表，并打开右侧生成网页的预览。

Each README uses six 1920×1080 images in its own interface language. Both conversation captures hide the history list and show the generated HTML page in the right sidebar.

| 内容 / Content                    | 中文 / Chinese                                                 | 英文 / English                                      |
| --------------------------------- | -------------------------------------------------------------- | --------------------------------------------------- |
| 工作台 / Workspace                | [workspace.jpg](screenshots/workspace.jpg)                     | [workspace.jpg](screenshots/en/workspace.jpg)       |
| 对话 / Conversations              | [conversation.jpg](screenshots/conversation.jpg)               | [conversation.jpg](screenshots/en/conversation.jpg) |
| 企业微信与飞书 / WeCom and Feishu | [wecom-notifications.jpg](screenshots/wecom-notifications.jpg) | [integrations.jpg](screenshots/en/integrations.jpg) |
| 待办 / To-dos                     | [todos.jpg](screenshots/todos.jpg)                             | [todos.jpg](screenshots/en/todos.jpg)               |
| 定时任务 / Schedules              | [schedules.jpg](screenshots/schedules.jpg)                     | [schedules.jpg](screenshots/en/schedules.jpg)       |
| 能力中心 / Capabilities           | [agents.jpg](screenshots/agents.jpg)                           | [agents.jpg](screenshots/en/agents.jpg)             |

## 来源与处理 / Sources and preparation

英文图片及中文工作台、对话于 2026-09-29 采集。浏览器返回的画面按原比例缩放并裁切至统一尺寸，不改写界面文字和业务记录。英文对话使用实际生成并导出的 Hello World 文件；产品记录名称保留原文。

The English images and the Chinese workspace and conversation images were captured on September 29, 2026. Browser captures were resized proportionally and cropped to the shared dimensions without changing interface text or records. The English conversation uses an actual generated and exported Hello World file. Record names remain unchanged.

中文企业微信图片由用户于 2026-09-29 提供，原图为 2352×1608。按原比例缩至宽度 1920 后保留上方 1080 像素，裁去底部输入框空白。英文版对应章节使用平台内的英文接入管理界面。

The Chinese WeCom image was supplied by the user on September 29, 2026 at 2352×1608. It was resized proportionally to 1920 pixels wide and cropped to the top 1080 pixels, removing blank space in the input area. The English README uses the platform's English integration settings.

其余三张中文图片来自维护工作区的 `doc/user-guide/assets/`，采集日期为 2026-09-24，保留原始字节和尺寸。原文件虽使用 `.png` 后缀，实际为 JPEG（常用图片格式），此处使用对应的 `.jpg` 后缀。

The other three Chinese images retain the original bytes and dimensions from `doc/user-guide/assets/`, captured on September 24, 2026. The originals use a `.png` extension but contain JPEG data; the copies use `.jpg`.

## 维护 / Maintenance

[screenshots.json](screenshots.json) 记录每张图的语言、来源、日期、尺寸、文件大小和 SHA-256（用于核对文件内容是否变化的摘要）。替换时检查历史记录、账号信息和凭据，保持 1920×1080，并同步更新清单及对应语言的 README。

[screenshots.json](screenshots.json) records the language, source, date, dimensions, file size, and SHA-256 checksum. Check history, account details, and credentials before replacing an image; keep the 1920×1080 dimensions and update the manifest and the corresponding README.

`cover.zh.svg` 和 `cover.en.svg` 沿用 `agenteam-web/src/app/icon.svg` 中的四瓣标志路径与铜色配色。

`cover.zh.svg` and `cover.en.svg` use the existing four-part brand mark from `agenteam-web/src/app/icon.svg` and the product's copper palette.
