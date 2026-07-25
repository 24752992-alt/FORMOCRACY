# RealityBridge.gd —— FORMOCRACY(Godot 4.x) 现实事件发射器
#
# 用法：
# 1. 项目设置 → 自动加载(AutoLoad)，把本文件加为单例，名字设为 RealityBridge。
# 2. 在游戏逻辑关键节点调用，例如：
#      RealityBridge.emit_reality_event("reality_receipt", {
#          "day": 5, "formId": "hospital_urgent_07",
#          "outcome": "void", "severity": "critical",
#          "title": "第七码头 · 医院急件",
#          "body": "文件未验收 · 现实未更新 · 触发死亡回执"
#      })
# 3. 把 DEFAULT_HOST 改成手机 App 状态栏显示的 IP（形如 ws://<手机IP>:8777 里那段）。
#    也可不改代码，用环境变量覆盖（换热点后手机 IP 变了很方便）：
#      GLASS_WS_HOST=172.20.10.22 GLASS_WS_PORT=8777 godot ...
# 4. 推荐直接调用下方按事件类型封装好的便捷方法（morning_briefing / secretary_react /
#    npc_line / reality_receipt / day_report ...），比手拼字典更省心。
#
# 说明：WebSocketPeer 在 Godot 原生导出与 Web(HTML5) 导出下都可用。
extends Node

# ── 连接目标：Rokid 手机 App 的局域网 IP + WebSocket 端口 ──
const DEFAULT_HOST := "192.168.1.100"   # ← 改成手机 App 状态栏显示的 IP
const DEFAULT_PORT := 8777              # 手机端 GameEventServer 固定端口
const RECONNECT_INTERVAL := 2.0         # 断线后重连间隔（秒）；避免每帧狂连

signal glass_connected                  # 连上手机时发出
signal glass_disconnected               # 断开时发出

var _host: String
var _port: int
var _ws := WebSocketPeer.new()
var _connected := false
var _pending: Array = []                # 未连接时暂存的事件
var _reconnect_left := 0.0              # >0 表示正在等待重连倒计时

func _ready() -> void:
	_host = _resolve_host()
	_port = _resolve_port()
	_connect()

func _resolve_host() -> String:
	var env := OS.get_environment("GLASS_WS_HOST")
	return env if env != "" else DEFAULT_HOST

func _resolve_port() -> int:
	var env := OS.get_environment("GLASS_WS_PORT")
	return int(env) if env.is_valid_int() else DEFAULT_PORT

func _connect() -> void:
	var url := "ws://%s:%d" % [_host, _port]
	print("[RealityBridge] 连接 %s ..." % url)
	var err := _ws.connect_to_url(url)
	if err != OK:
		push_warning("[RealityBridge] 连接失败: %s（%.0fs 后重试）" % [err, RECONNECT_INTERVAL])
		_reconnect_left = RECONNECT_INTERVAL

func _process(delta: float) -> void:
	# 断线后按固定间隔重连，避免每帧狂连
	if _reconnect_left > 0.0:
		_reconnect_left -= delta
		if _reconnect_left <= 0.0:
			_ws = WebSocketPeer.new()
			_connect()
		return

	_ws.poll()
	match _ws.get_ready_state():
		WebSocketPeer.STATE_OPEN:
			if not _connected:
				_connected = true
				print("[RealityBridge] 已连接 %s:%d" % [_host, _port])
				glass_connected.emit()
				# 冲刷积压事件
				for e in _pending:
					_ws.send_text(e)
				_pending.clear()
		WebSocketPeer.STATE_CLOSED:
			if _connected:
				print("[RealityBridge] 连接断开，%.0fs 后重连" % RECONNECT_INTERVAL)
				glass_disconnected.emit()
			_connected = false
			_reconnect_left = RECONNECT_INTERVAL

## 是否已连上手机（供游戏侧判断）
func is_connected_to_glass() -> bool:
	return _connected

# 底层接口：发射一条现实事件（type + 任意字段）。一般用下面封装好的便捷方法即可。
func emit_reality_event(type: String, data: Dictionary = {}) -> void:
	var payload := data.duplicate()
	payload["type"] = type
	var text := JSON.stringify(payload)
	if _connected:
		_ws.send_text(text)
	else:
		_pending.append(text)   # 等连上再一次性发（离线也不丢事件）

# ─────────────────────────────────────────────────────────────
# 便捷封装：按 RealityEvent 协议给游戏逻辑直接调用（字段含义见眼镜端 RealityEvent.kt）
# ─────────────────────────────────────────────────────────────

## 晨间指令（Day N）：逐行居中列表显示，不朗读
func morning_briefing(day: int, lines: Array, title := "") -> void:
	emit_reality_event("morning_briefing", {
		"day": day,
		"title": title if title != "" else "晨间指令 · Day %d" % day,
		"lines": lines,
	})

## 每日结算：逐行居中列表显示，不朗读
func day_report(lines: Array, day := 0, title := "每日结算") -> void:
	var d := {"title": title, "lines": lines}
	if day > 0: d["day"] = day
	emit_reality_event("day_report", d)

## 秘书据局势即兴反应（云端 agent 生成台词+语音）。phase: "intake"(到台) | "verdict"(盖章后)
## applied 省略(<0)则不上报玩家已贴金额（intake 阶段建议省略）
func secretary_react(phase: String, parcel_no: int, weight: int, dest: String, due: float, applied := -1.0) -> void:
	var d := {
		"phase": phase, "parcelNo": parcel_no, "weight": weight,
		"dest": dest, "due": due,
	}
	if applied >= 0.0: d["applied"] = applied
	emit_reality_event("secretary_react", d)

## 秘书固定台词（直接朗读 text）
func secretary_line(text: String) -> void:
	emit_reality_event("secretary_line", {"text": text})

## 秘书晨报谈资准备（能力①）：把今日晨报 + 玩家过往决策发给眼镜端秘书，
## 由其摘要成“谈资”并缓存（不出声、不上镜，纯后台准备）。建议在玩家读完晨报/到公司前调用一次。
##   newspaper: Array[Dictionary]，每项 {"headline": String, "body": String(可选)}
##   decisions: Array[Dictionary]，每项 {"formId": String, "title": String, "decision": "approved|rejected|held", "day": int}
func secretary_daybrief(day: int, newspaper: Array, decisions: Array = []) -> void:
	emit_reality_event("secretary_daybrief", {
		"day": day,
		"newspaper": newspaper,
		"decisions": decisions,
	})

## 秘书晨报闲聊开场（场景①）：玩家到公司时触发，秘书借当天谈资阴阳过去的批复。
## 需先调过 secretary_daybrief 准备谈资（可紧跟其后调用）。
func secretary_briefing_chat(day := 0) -> void:
	var d := {}
	if day > 0: d["day"] = day
	emit_reality_event("secretary_briefing_chat", d)

## 秘书选表单评论（场景②）：玩家每次把一份候选表单加入/撤出验收队列时调用，
## 秘书据随机心情做一句心理暗示/讽刺。action: "add"(加入) | "remove"(撤出)。
## fact_hint 可选：该表单已知后果的客观提示，供秘书阴阳时参考。
func secretary_pick_comment(form_id: String, title: String, action: String, remaining_slots := -1, fact_hint := "") -> void:
	var d := {"formId": form_id, "title": title, "action": action}
	if remaining_slots >= 0: d["remainingSlots"] = remaining_slots
	if fact_hint != "": d["factHint"] = fact_hint
	emit_reality_event("secretary_pick_comment", d)

## 实时搭话开始（场景③，push-to-talk）：玩家按住“说话键”时调用，
## 手机端会打断秘书当前朗读并开启眼镜麦克风录音。需与 secretary_chat_stop 成对使用。
func secretary_chat_start() -> void:
	emit_reality_event("secretary_chat_start", {})

## 实时搭话结束：玩家松开“说话键”时调用。手机端停录音 → 整段语音转文字 →
## 秘书结合多轮记忆与当日谈资生成回复并朗读（录音过短会被忽略）。
func secretary_chat_stop() -> void:
	emit_reality_event("secretary_chat_stop", {})

## NPC 出场说话：左侧头像 + 打字机台词。gender: male|female，age: young|average|old
func npc_line(name: String, text: String, gender := "", age := "", portrait := "") -> void:
	var d := {"title": name, "text": text}
	if gender != "": d["gender"] = gender
	if age != "": d["age"] = age
	if portrait != "": d["portrait"] = portrait
	emit_reality_event("npc_line", d)

## 现实回执：卡片显示（title + body）。severity: normal|warning|critical
func reality_receipt(title: String, body: String, severity := "normal", day := 0, form_id := "", outcome := "") -> void:
	var d := {"title": title, "body": body, "severity": severity}
	if day > 0: d["day"] = day
	if form_id != "": d["formId"] = form_id
	if outcome != "": d["outcome"] = outcome
	emit_reality_event("reality_receipt", d)

## 后果回流：卡片显示
func consequence(title: String, body: String, severity := "warning") -> void:
	emit_reality_event("consequence", {"title": title, "body": body, "severity": severity})

## 连通性自测：连上后发一张测试卡片，眼镜上应立即出现
func send_test() -> void:
	reality_receipt("连接测试", "Godot ↔ 眼镜 链路已打通", "normal")
