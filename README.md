# survey-local-demo 项目说明

这是一个本地问卷演示填报器。程序通过本地网页上传 Excel，配置 Excel 列与问卷题目的映射，然后用本机可见 Chrome/Edge 浏览器逐行填写问卷；默认只填写并截图，开启“点击提交”后会真实点击提交按钮。

## 快速启动

```powershell
cd "E:\问卷软件制作\survey-local-demo"
.\gradlew.bat run
```

启动后打开：

```text
http://localhost:8080
```

本地样例问卷：

```text
http://localhost:8080/sample-form.html
```

## 核心流程

1. 在网页上传 Excel 文件。
2. 预览 Excel 前 10 行，确认列名和数据内容。
3. 配置字段映射：Excel 列、问卷题目、题型、是否必填、题号偏移等。
4. 设置行范围、每行间隔、填写总耗时、来源比例、是否启用提交。
5. 后端使用 Playwright Java 创建可见浏览器上下文。
6. 每一行单独打开浏览器上下文，按映射填写题目，必要时翻页。
7. 未启用提交时保存 `row-N-filled.png` 供人工检查。
8. 启用提交时点击提交按钮，等待完成页信号，再保存 `row-N-submitted.png`。
9. 成功、失败、截图路径、原始行数据都会写入任务日志。

## 问卷星提交页兼容

问卷星提交成功后不一定进入普通感谢页。有些问卷开启了抽奖或激励功能，移动端或微信 UA 下会跳转到：

```text
https://v.wjx.cn/wjx/join/completemobile2...
```

页面通常包含“您的答卷已经提交”“恭喜您获得了 1 次抽奖机会”“立即抽奖”等内容。项目现在会把这些页面同样识别为提交完成：

- URL 包含 `completemobile`、`/wjx/join/complete` 或 `/join/complete`。
- 页面文本包含“您的答卷已经提交”“感谢您的参与”“恭喜您获得了”“立即抽奖”或“抽奖机会”。
- 最长等待 12 秒；如果没有检测到完成信号，会再短暂等待 2 秒后继续，避免单行无限卡住。
- 提交后截图使用 `row-N-submitted.png`，未提交预览截图仍使用 `row-N-filled.png`。
- 每行结束时先把页面跳到 `about:blank`，再由外层浏览器上下文统一释放，避免抽奖页导致当前标签页关闭卡住。

## 主要代码文件

- `src/main/kotlin/com/localform/Application.kt`：Ktor 入口和 HTTP 路由。
- `src/main/kotlin/com/localform/Models.kt`：前后端请求、任务、映射等数据模型。
- `src/main/kotlin/com/localform/ExcelService.kt`：Excel 读取、预览、自动映射、IP 写回。
- `src/main/kotlin/com/localform/TaskStore.kt`：任务状态、日志、成功失败计数和截图记录。
- `src/main/kotlin/com/localform/QuestionnaireRunner.kt`：核心自动填写、翻页、提交、截图、完成页检测逻辑。
- `src/main/kotlin/com/localform/automation/browser/ContextInitializer.kt`：浏览器上下文、UA、移动端/微信端指纹配置。
- `src/main/kotlin/com/localform/automation/behavior/HumanBehaviorSimulator.kt`：模拟滚动、延迟、点击等行为。
- `src/main/resources/static/index.html`、`app.js`、`styles.css`：本地网页前端。
- `src/main/resources/static/sample-form.html`：本地样例问卷。
- `config/automation-profiles.json`：自动化配置档案。

## 运行数据目录

运行时数据默认保存在项目下：

```text
E:\问卷软件制作\survey-local-demo\.survey-local-demo
```

常见子目录：

- `.survey-local-demo/uploads`：上传的 Excel 文件副本。
- `.survey-local-demo/screenshots/<taskId>`：每行截图。
- 任务日志通过本地服务接口展示在页面中。

## 默认限制

- 顺序执行，不做并发提交。
- 最小间隔 2 秒。
- 单次最多 50 行。
- 默认不点击提交按钮，只填写并截图。
- 浏览器以可见模式打开，便于人工审查。
- 启用提交后每行不自动重试，避免重复提交。
- 失败会记录行号、原始行数据、错误原因和截图路径。

## Excel 示例

示例列：

```text
姓名    性别    年级    满意度    建议
张三    男      大二    满意      无
李四    女      大三    一般      希望改进
```

示例映射：

| Excel 列 | 问卷题目 | 题型 |
| --- | --- | --- |
| 姓名 | 请输入你的姓名 | 填空题 |
| 性别 | 你的性别 | 单选题 |
| 年级 | 你的年级 | 下拉题 |
| 满意度 | 你是否满意 | 单选题 |
| 建议 | 你的建议 | 填空题 |

## 常见排障

- 如果停在抽奖页，先确认日志中是否出现 `submit completion page detected`。出现该日志说明已识别为提交完成，后续应进入下一行。
- 如果日志停在 `fillRow completed successfully` 且没有 `Row N succeeded on attempt`，优先检查页面或上下文关闭阶段。
- 如果出现 IP 记录失败，只影响 IP 写回，不代表问卷填写失败。
- 如果问卷星出现安全验证，检查验证码处理配置和每行填写耗时，不要把耗时设置过短。
- 如果 `rg`、Chrome 或 Gradle 命令不可用，优先检查本机权限、Chrome 安装路径和 JDK 21 环境。
