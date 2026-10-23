# oh-my-token

IntelliJ 系 IDE 的状态栏插件，实时显示 Claude Code 的 token 消耗。

数据来自本地 `~/.claude/projects` 下的会话记录，按 message id 去重，口径和 ccusage 一致。所有数据只在本地处理，不联网。

## 功能

- 状态栏显示输出速率（tok/s）和累计用量
- 统计范围：今日 / 近 7 天 / 近 30 天 / 全部 / 指定日期
- 5 小时滚动窗口限额监控，按当前速率预测打满时间
- 会话上下文占用预警（超过阈值时状态栏提示）
- 分模型、分项目明细（鼠标悬停查看）
- 用量趋势图：今日逐小时 + 近 30 天逐日
- 中英文界面

## 安装

Marketplace 审核通过后可直接搜索安装。手动安装：

```bash
./gradlew buildPlugin
```

产物在 `build/distributions/`，IDE 里 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择 zip 即可。

## 设置

`Settings → Tools → Oh My Token`：

- 默认统计范围
- 状态栏是否显示速率 / 总量
- 5 小时限额（token 数，0 为不显示）
- 上下文窗口大小与告警阈值
- Claude 数据目录（默认 `~/.claude/projects`）

## 开发

需要 JDK 17+，无需单独安装 Gradle，用仓库里的 wrapper：

```bash
./gradlew runIde          # 起沙箱 IDE 调试
./gradlew buildPlugin     # 打包
```

首次构建会下载 IntelliJ SDK（1GB 左右）。Windows 用户注意 wrapper 默认走腾讯镜像下载 Gradle 发行包。

## 发布

1. 在 [plugins.jetbrains.com](https://plugins.jetbrains.com) 注册账号，后台生成 Marketplace token
2. 发布前跑一次兼容性验证（会下载多个版本 IDE，比较慢）：

   ```bash
   ./gradlew runPluginVerifier
   ```

3. 发布：

   ```bash
   PUBLISH_TOKEN=你的token ./gradlew publishPlugin
   ```

   或者直接在 Marketplace 后台手动上传 `build/distributions/` 下的 zip。

## License

MIT
