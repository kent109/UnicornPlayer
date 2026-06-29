# 编码规范

## 文件换行符

创建新生文件时（不管是什么类型），换行符要使用 CRLF，不能使用 LF 换行符！

## 包结构

创建新类时，要根据类的用途和分类，考虑是否新建子包（比如 `component`、`util` 等），不能总是放在 `com.unicorn.player` 包下。

## 代码复用

对于高度重复的代码，要提取为公共方法。

## ViewBinding

项目已启用 ViewBinding，所有 Activity/Fragment 使用生成的绑定类（如 `ActivityMainBinding`），无需 `findViewById`。

## 交互语言

开发者是中文环境，在发送和接收指令时请使用简体中文。
