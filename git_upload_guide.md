# GitHub上传指南 - 新分支创建

## 本次更新内容摘要
本次更新包含以下主要功能：

### 1. 画布记忆功能
- 记住上次的笔刷类型（普通/粉笔）
- 记住上次的背景颜色（黑色/白色）
- 应用启动时自动恢复设置

### 2. 图片数量优化
- 最大图片数量从4张增加到5张
- 内存优化逻辑相应调整

### 3. 剪贴板自动功能
- 第5次书写时自动将第4张图片保存到剪贴板
- 第4张图片直接消失，显示第5张
- 显示完成后自动从剪贴板读取并重新显示第4张图片

### 4. 书写粗细优化
- 固定书写粗细，减少不均匀现象
- 移除动态粗细调整
- 统一加粗系数，提高一致性

## Git操作步骤

### 步骤1：检查当前状态
```bash
# 检查当前分支和状态
git status
git branch
```

### 步骤2：创建新分支
```bash
# 创建并切换到新分支（建议使用描述性名称）
git checkout -b feature/canvas-memory-and-optimizations

# 或者分别执行
git branch feature/canvas-memory-and-optimizations
git checkout feature/canvas-memory-and-optimizations
```

### 步骤3：添加所有更改
```bash
# 添加所有修改的文件
git add .

# 或者分别添加特定文件
git add app/src/main/java/com/example/newdrawingapp/ClientActivity.kt
git add app/src/main/java/com/example/newdrawingapp/HiddenDrawingView.kt
git add app/src/main/java/com/example/newdrawingapp/DrawingView.kt
```

### 步骤4：提交更改
```bash
git commit -m "feat: 添加画布记忆功能和书写优化

- 新增画布记忆功能：记住笔刷类型和背景颜色
- 图片数量从4张增加到5张
- 添加剪贴板自动功能：第5次书写时自动处理第4张图片
- 优化书写粗细一致性，减少不均匀现象
- 修复相关语法错误和内存优化逻辑"
```

### 步骤5：推送到GitHub
```bash
# 首次推送新分支到远程仓库
git push -u origin feature/canvas-memory-and-optimizations

# 后续推送可以直接使用
git push
```

## 分支命名建议
根据本次更新的功能，建议使用以下分支名称之一：
- `feature/canvas-memory-and-optimizations`
- `feature/writing-improvements`
- `feature/clipboard-auto-function`
- `enhancement/canvas-consistency`

## 创建Pull Request（可选）
1. 访问你的GitHub仓库
2. 点击 "Compare & pull request" 按钮
3. 填写PR标题和描述
4. 选择目标分支（通常是main或master）
5. 创建Pull Request

## 注意事项
1. 确保你的本地仓库是最新的（git pull）
2. 在推送前检查是否有冲突需要解决
3. 建议在推送前运行测试确保功能正常
4. 如果有敏感信息，请检查.gitignore文件

## 回滚操作（如需要）
如果需要撤销某些更改：
```bash
# 撤销最后一次提交但保留更改
git reset --soft HEAD~1

# 完全撤销最后一次提交和更改
git reset --hard HEAD~1

# 撤销特定文件的更改
git checkout HEAD -- 文件名
```
