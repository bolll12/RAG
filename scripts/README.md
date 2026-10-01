# 本地工具

`maven.sh` 优先使用项目 `.tools` 下的 Maven 3.9.11 和 JDK 21，否则使用系统 Maven/JDK。

`run.sh` 从项目目录启动可执行 JAR，确保 `.env` 与默认数据库的相对路径一致。可附加 `ingest` / `eval` 命令或 Spring Boot 参数。

`.tools` 是本机专用、被 Git 忽略的工具缓存；部署不依赖该目录。其他机器安装 JDK 21 和 Maven 3.6.3+ 后可直接执行 `mvn test package`、`java -jar target/rag-service-1.0.0.jar`。Docker 使用 Eclipse Temurin 构建并运行。

`ollama.sh` 使用项目 `.tools/ollama` 中的 Ollama 0.34.4，模型保存在 `data/ollama-models`，监听 `127.0.0.1:11434`。运行 `scripts/ollama.sh serve` 启动模型服务，另一个终端执行 `scripts/ollama.sh pull bge-m3` 下载模型。服务停止后可重新执行 `serve`，无需重复下载。安装包来自 Ollama 官方发布并校验 SHA-256。
