# FORMOCRACY：表面政治

> 游戏之内，眼镜之上，表单即权力。

**FORMOCRACY** 是一个为空间计算硬件（Rokid AR 智能眼镜）原生设计的严肃叙事游戏 —— AdventureX 2026 参赛项目。

玩家扮演失忆的**首席审批官**，数字办公场景（AI 秘书、NPC 对话、审批表单）直接叠加在现实空间中。核心机制创新在于将「批准」与「生效」分离：只有被验收机吞入的文件才会真正改变世界。通过有限槽位与连锁后果，游戏探讨制度性责任与程序化暴力。

> 当游戏不再隔着屏幕，叙事的道德重量会不会真正压在玩家肩上？

---

## 系统架构

```
┌──────────────┐   ws:// JSON 事件    ┌───────────────────────────┐   蓝牙/WiFi-P2P   ┌─────────┐
│ Godot 游戏    │ ──────────────────► │ 眼镜手机 App (WS 服务:8777) │ ───────────────► │ AR 眼镜  │
│              │                     │  收事件 → 渲染卡片/朗读台词  │                  │         │
└──────────────┘                     └───────────────────────────┘                  └─────────┘
```

游戏端作为 WebSocket 客户端，直连眼镜手机 App（局域网），无需任何中间服务器或云端。

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| [`glass-bridge-android/`](glass-bridge-android/) | AR 眼镜端 Android 应用：内置 WebSocket 服务（端口 8777），接收游戏事件并在眼镜波导屏上渲染 NPC 卡片、秘书动画，同时合成语音播报（含秘书/NPC 立绘素材与音效资源） |
| [`glass-bridge-delivery/`](glass-bridge-delivery/) | 游戏端对接交付包：Godot 桥接单例 `RealityBridge.gd` + 《眼镜对接手册》集成指南 |
| [`game-app/`](game-app/) | 灵光闪（Lingguang）Web 应用：世界观展示、NPC 人物设定、AI 秘书文字对话三大板块 |

## 技术栈

**眼镜端（glass-bridge-android）**
- Kotlin / Android，WebSocket 服务端接收游戏事件
- AI 秘书链路（阶跃星辰 StepFun 云端模型）：
  - LLM：`stepaudio-2.5-chat`（实时生成秘书台词）
  - ASR：`step-asr`（玩家语音转文字，Whisper 兼容）
  - TTS：`stepaudio-2.5-tts`（干练女声），断网时回退科大讯飞本地 TTS
- 秘书动画帧率 ≤ 4 FPS（波导显示硬性约束）

**游戏端（glass-bridge-delivery）**
- Godot 4 / GDScript，`RealityBridge.gd` 注册为 AutoLoad 单例后即可向眼镜推送事件

**灵光闪应用（game-app）**
- React + TypeScript + Vite，UI 使用 lucide-react，动效使用 framer-motion
- AI 对话接入宿主 `lingguang.ai.llmStream` 流式大模型，降级链路：`callLLM` → 本地兜底台词

## 快速开始

### 眼镜端 App

```bash
# 1. 在 glass-bridge-android/ 下创建 local.properties，填入你自己的密钥（不入版本控制）
#    STEP_API_KEY=<你的阶跃星辰 API Key>   # 留空则秘书语音回退手机本地 TTS
# 2. 用 Android Studio 打开 glass-bridge-android/ 构建安装到眼镜手机
```

### 游戏端接入

游戏所在电脑与眼镜手机连同一热点，将 `glass-bridge-delivery/RealityBridge.gd` 挂入 Godot 工程 AutoLoad，填入眼镜手机 App 状态栏显示的 IP 即可。完整步骤见 [眼镜对接手册](glass-bridge-delivery/眼镜对接手册.md)。

### 灵光闪应用

```bash
cd game-app
npm ci
npm run dev      # 本地开发
npm run check    # 质量门禁：lint + typecheck + build
```

## 安全说明

所有 API 密钥（阶跃星辰等）仅存于 `local.properties` 等本地配置文件，已通过 `.gitignore` 排除，源码中无任何硬编码密钥。克隆本仓库后需自行申请并配置密钥。

## License

本项目为 AdventureX 2026 参赛作品，版权归项目团队所有。
