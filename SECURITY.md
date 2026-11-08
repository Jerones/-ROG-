# 发布说明与隐私处理

本仓库是「基于 RAG 的施工安全规范智能问答系统」的公开发布版。
代码与本地开发版一致，只做了**凭据移除**和**本机相关文件剔除**，功能没有任何删减。

---

## 一、已处理的隐私 / 环境相关项

| 项目 | 原始位置 | 处理方式 |
|---|---|---|
| **真实模型 API Key** | `src/main/resources/application.yml` → `spring.ai.openai.api-key` 的默认值 | 已替换为占位符 `sk-placeholder-set-LLM_API_KEY-in-env`，真实 key 只能通过环境变量 `LLM_API_KEY` 注入 |
| **本机 hosts 文件** | 项目根目录 `hosts` | 不提交（已加入 `.gitignore`）。该文件由 `tools/pin_dashscope_ip.py` 按当前网络探活生成，换机器/换网络后应重新生成或直接删除 |
| **上传的规范原文** | `data/docs/` | 不提交。里面是测试时上传的 GB 50656-2011、GB 50720-2011 等标准全文，版权归原发布单位，不适合随仓库分发 |
| **向量库快照** | `data/simple-vector-store.json`（约 21 MB）、`data/registry.json` | 不提交。首次运行会自动重建 |
| **运行日志 / PID** | `.run.log`、`.run.pid` | 不提交 |
| **IDE 工程文件** | `.idea/` | 不提交。其中 `workspace.xml` 记录了本机绝对路径 |
| **构建产物** | `target/`（含 81 MB 胖 jar） | 不提交，用 `mvn package` 自行构建 |
| **备份目录** | `backup/` | 不提交 |

`tools/pin_dashscope_ip.py` 源码中出现的 4 个 IP（`8.140.217.18` 等）是 `dashscope.aliyuncs.com`
的**公网 A 记录**，属于公开 DNS 信息，不涉及任何内网地址或账号信息，因此随代码保留。

`sample_docs/施工安全规范（节选示例）.txt` 是自拟的演示文本（文首已注明），不是任何正式规范的原文。

---

## 二、克隆后如何配置自己的 Key

**不要把 key 写回 `application.yml`**（否则下次提交又会带出去）。三种注入方式任选其一：

**方式 1：环境变量（推荐）**

```bash
# Linux / macOS / Git Bash
export LLM_API_KEY="sk-你的key"
export APP_DEMO_MODE=false      # 用真实模型
```

```powershell
# Windows PowerShell
$env:LLM_API_KEY = "sk-你的key"
$env:APP_DEMO_MODE = "false"
```

**方式 2：IDEA 运行配置**

`Run → Edit Configurations → Environment variables` 填：

```
LLM_API_KEY=sk-你的key;APP_DEMO_MODE=false
```

**方式 3：命令行启动**

```bash
java -jar target/safety-rag-qa-1.0.0.jar \
  --spring.ai.openai.api-key="sk-你的key" \
  --app.demo-mode=false
```

**完全没有 key 时**，把 `APP_DEMO_MODE` 设为 `true`：
系统改用本地兜底 Embedding（字符 n-gram 哈希）+ 抽取式回答，不调用任何外部 API，
但向量检索质量和回答自然度都会明显下降，仅适合跑通链路、演示交互。

可覆盖的配置项：

| 环境变量 | 含义 | 默认值 |
|---|---|---|
| `LLM_API_KEY` | 模型 API key | 占位符（必须替换） |
| `LLM_BASE_URL` | OpenAI 兼容端点 | `https://dashscope.aliyuncs.com/compatible-mode` |
| `LLM_CHAT_MODEL` | 对话模型名 | `qwen-plus` |
| `LLM_EMBEDDING_MODEL` | 向量模型名 | `text-embedding-v3` |
| `APP_DEMO_MODE` | 是否离线演示模式 | `false` |
| `APP_DATA_DIR` | 数据目录 | `./data` |

---

## 三、克隆后首次运行

```bash
git clone https://github.com/Jerones/ROG.git
cd ROG

# 1) 配 key（见上一节）
export LLM_API_KEY="sk-你的key"
export APP_DEMO_MODE=false

# 2) 构建
mvn -q package -DskipTests

# 3) 启动（默认 8080）
java -jar target/safety-rag-qa-1.0.0.jar

# 浏览器打开 http://localhost:8080
```

`data/` 目录、向量库文件、`hosts` 都会在首次启动时按需自动创建，无需手工准备。
完整的架构说明、接口列表、踩坑记录见 [README.md](README.md)。

---

## 四、如果你 fork 了本仓库

1. 提交前先跑一遍密钥扫描：

   ```bash
   git grep -nE "sk-[A-Za-z0-9._-]{16,}" || echo "clean"
   ```

2. 建议开启 GitHub 的 **Secret scanning**（Settings → Code security）。
3. 万一误提交了真实 key，**立刻去控制台吊销并重新签发**——只删提交没用，
   历史记录、fork、缓存里都还留着。

---

## 五、使用范围

- 代码以学习、课程设计与技术交流为目的公开，可自由参考。
- 知识库中的国家标准、行业标准原文版权归原发布单位所有，本仓库不分发这些全文；
  正式施工的安全依据请查阅现行标准正式版本。
