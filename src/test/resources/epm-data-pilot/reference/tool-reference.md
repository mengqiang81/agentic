---
name: tool-reference
description: epm-data-pilot 技能所依赖的 MCP 工具参数规范、调用示例和返回结构说明
---

# 工具参数参考

## 工具清单

| 工具 | 功能 | 必填参数 | 可选参数 |
|------|------|----------|----|
| `bgm_ai_globalId_create` | 获取代表当前会话的唯一 `sessionId` | 无 | 无 |
| `bgm_ai_model_query` | 查询当前用户有权限的体系 | 无 | 无 |
| `bgm_ai_member_match` | 获取与用户提问关联的候选维度成员 | `modelNumber`，`userQuestion` | 无 |
| `bgm_ai_template_query` | 查询当前用户有权限且与提问相关的模板 | `modelNumber` | `dimAndMemberList` |
| `bgm_ai_template_structure_query` | 查询模板关联的维度和维度成员信息 | `templateId` | `dimAndMemberList` |
| `bgm_ai_data_trace` | 数据查询或追踪 | `sessionId`，`modelNumber`，`items` | 无 |
| `bgm_ai_data_node_trace` | 针对已获取数据的节点继续追踪 | `sessionId`, `id` | `dimAndMemberList` |

## 通用参数说明

所有其它工具的调用参数均需包装在 `params` 对象中，例如：

```json
{ "params": { "modelNumber": "YS_DEMO_AI", "userQuestion": "营业利润" } }
```

## 维度参数说明

`bgm_ai_template_query` 工具支持可选参数 `dimAndMemberList` 用于提供已经从用户对话中识别的维度和维度成员编码，提升模板查找精度。

`bgm_ai_template_structure_query` 工具支持可选参数 `dimAndMemberList` 用于避免系统返回已识别的维度和维度成员。

参数结构如下：

```json
[
  { "dimNumber": "维度编码1", "memberNumber": "维度成员编码1" },
  { "dimNumber": "维度编码2", "memberNumber": "维度成员编码2" }
]
```

## bgm_ai_data_trace 调用参数

需要一次追踪多个单元格可以添加多个对象，在 items 中：

```json
{
    "params": {
        "sessionId": "2468198431941033985",
        "modelNumber": "YS_DEMO_AI",
        "items": [{
                "dimAndMemberList": [{
                        "dimNumber": "Entity",
                        "memberNumber": "HYJD_GZ"
                    }, {
                        "dimNumber": "Account",
                        "memberNumber": "YYSR"
                }]
        }]
    }
}
```

## bgm_ai_data_trace 返回结构

该工具一次调用会按照广度优先原则返回多个层级的上游关联追踪数据，系统会自动把多次调用的结果挂到对应的 children 中：

```json
{
	"id": "单元格唯一标识",
	"value": "关联单元格的值",
	"description": "维度:维度成员,维度:维度成员,维度:维度成员",
	"cellType": "单元格类型：聚合: AGG，公式: RULE，录入: INPUT，注意：类型为AGG、RULE代表当前单元格可以继续追踪",
	"aggType": "单元格用于聚合时采用的算法1:代表加，2:代表减，5:代表忽略",
	"aggregationDim": [{
		"dimName": "组织",
		"dimNumber": "Entity",
		"children": [{},{}]
	},{
		"dimName": "期间",
		"dimNumber": "Period",
		"children": [{},{},{}]
	}],
	"rule": {
		"ruleShow": "=${1001}+${1002}",
		"children": [{"id": "1001", "value": "...", "cellType": "..."},{"id": "1002"}]
	},
	"otherCombinationList": [{
		"value": "4575500.00",
		"versionName": "年度预算V1版",
		"versionNumber": "ANNUAL_V1",
		"dataTypeName": "年度预算",
		"dataTypeNumber": "Annual"
	}]
}
```
### 公式单元格逻辑说明
- `ruleShow` 属性代表公式，其中${id},代表上游单元格id
- `children` 公式引用到的上游单元格对象，id于`ruleShow` 中${id}一致。

### 聚合运算逻辑说明

当单元格类型为 `AGG` 时，父节点的数值由 `aggregationDim` 中各 `children` 的数值按 `aggType` 运算得出：

- `aggType: 1`：子节点值**相加**
- `aggType: 2`：子节点值**相减**
- `aggType: 5`：忽略该子节点

例如：营业利润 = 营业收入(aggType:1) - 营业总成本(aggType:2)。

`bgm_ai_data_node_trace` 返回结构同 `bgm_ai_data_trace` 中的单元格对象，字段含义一致。

## 期间维度编码规则

当用户提问涉及到的期间未出现在模板返回的候选维度成员中时，可以采用如下原则获取期间维度的成员编码：

```properties
"预算期间"维度编码：BudgetPeriod,
期间编码规则（请严格遵循并应用）：
    - 年份：四位数字，例如 2025。
    - 半年度：HF1 (上半年，1-6月)；HF2 (下半年，7-12月)。
    - 季度：Q1 (1-3月)；Q2 (4-6月)；Q3 (7-9月)；Q4 (10-12月)。
    - 月份：M01；M02；M03；M04；M05；M06；M07；M08；M09；M10；M11；M12 (对应一月到十二月)。
输出格式：
     - 基本格式为：FY[年份] 或 FY[年份].[时间段编码]。
     - 用户提问未明确年时默认为2026年，相应的去年或上一年（2025年：FY2025）
例子：
     - 年份。2025年：FY2025。2026年：FY2026。2027年：FY2027。
     - 半年度。2025年上半年：FY2025.HF1。2026年半年：FY2026.HF2。
     - 季度。2025年1季度：FY2025.Q1。2025年2季度：FY2025.Q2。2025年上半年1季度：FY2025.Q1。2025年下半年3季度：FY2025.Q3。
     - 月份。2025年1月：FY2025.M01。2026年2月：FY2026.M02。2025年第一季度三月：FY2026.M03。
```
