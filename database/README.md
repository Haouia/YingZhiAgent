# 数据库

schema 与 backend/src/main/resources/schema.sql 保持一致；应用首次启动通过参数化代码读取 classpath demo.json 导入种子数据，不依赖旧项目数据库。后续新增字段放入 migrations，不能用清库代替迁移。

