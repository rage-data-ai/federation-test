# 给代理的约定

## 改代码前先对齐 main

每次开始改这个仓库之前：

1. 执行 `git fetch origin main`。
2. 从这次拉到的 `origin/main` 开工作分支。人已经在别的分支上时，先把该分支变基或合并到这个 `origin/main`，再继续改文件。
3. 完成标准：当前 `HEAD` 的历史包含这次 fetch 到的 `origin/main`，并且 `git status` 里没有停在更旧基线上的未提交改动。

下一次动手前如果 `origin/main` 又前进了，从第 1 步重做。这样本次 diff 只包含这次要改的内容。

## 本地运行对齐 main

启动或重启本机网关、控制台或其他长期进程之前：

1. 执行 `git fetch origin main`。
2. 检出 `main`，并快进到这次拉到的 `origin/main`。
3. 用这个提交启动进程。
4. 完成标准：`git rev-parse HEAD` 等于 `git rev-parse origin/main`，并且正在运行的进程是在这次检出之后启动的。
