## 关联任务

<!-- 填写任务编号，如 F2 / A1 / B3 / I1 -->

## 这个 PR 做了什么

<!-- 1–3 条，描述用户能看到的变化，不要只写“完成功能” -->

-
-

## 验证

### 命令

- [ ] `cd QQMessageAssistantVue && npm run type-check`
- [ ] `cd QQMessageAssistantVue && npm run build`

<!-- 粘贴关键输出，或说明通过 -->

### 手动步骤

1.
2.

### 截图 / 录屏

<!-- 有界面变化时必填 -->

## 影响范围

- 涉及页面：
- 涉及文件：
- 是否修改公共文件（路由 / `http.ts` / `settings.ts` / `types/api.ts` / `package.json` / 布局）：是 / 否
  - 若是，请说明原因并 @ 对应负责人：

## 自查

- [ ] 只修改了本任务允许的文件
- [ ] 没有 `any`、`@ts-ignore`、`as unknown as`
- [ ] 组件里没有直接使用 `axios` / `fetch`
- [ ] 列表页有 loading / empty / error 三态
- [ ] 删除、确认、取消有二次确认
- [ ] 没有新增依赖
- [ ] 没有 `console.log`、`debugger`、写死的测试数据
- [ ] 接口路径、字段、枚举与 `design/api_contract.md` 或 Swagger 一致
- [ ] 已删除无用代码与临时调试文件

## 评审人重点看

1. **边界**：是否改了职责外的文件
2. **契约**：字段与枚举是否正确
3. **可验证性**：按描述能否复现

## 作者确认

- [ ] 我能解释本 PR 的每一处改动
- [ ] 里程碑演示时，我可以独立讲解并操作这个页面
