package com.formocracy.glassbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var server: GameEventServer? = null
    private lateinit var bridge: RealityGlassBridge

    private val wsPort = 8777

    // 状态栏定时刷新：连上热点后自动显示 IP，无需重启 App。
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRefresher = object : Runnable {
        override fun run() {
            updateStatus()
            statusHandler.postDelayed(this, 3000)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) log("权限已全部授予")
            else log("以下权限被拒绝：$denied（可能影响连接眼镜）")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        bridge = RealityGlassBridge(this) { runOnUiThread { log(it) } }
        bridge.init()

        findViewById<Button>(R.id.btnRequestPerm).setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { bridge.connect() }
        findViewById<Button>(R.id.btnConnect).setOnLongClickListener {
            log("长按触发：断开重置会话")
            bridge.disconnect(); true
        }
        findViewById<Button>(R.id.btnStartServer).setOnClickListener { startServer() }
        findViewById<Button>(R.id.btnTestForm).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "day_report",
                    title = "DAILY SETTLEMENT",
                    lines = listOf(
                        "Approved    12",
                        "Rejected     3",
                        "Errors       2",
                        "Wages   $48.00"
                    )
                )
            )
        }
        // 单测晨报：沿用“无头像·逐行居中”结算布局（morning_briefing 与 day_report 同一套呈现）。
        findViewById<Button>(R.id.btnTestBriefing).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "morning_briefing", day = 5,
                    title = "晨间指令 · Day 5",
                    lines = listOf(
                        "今日配额：12 件",
                        "重点排查：医院急件",
                        "违规将触发死亡回执"
                    )
                )
            )
        }
        // 单测流式 agent 通道（不看 ENABLED 开关）：走 secretary_react 全链路——
        // 桥把结构化局势拼成情境简报喂给阶跃多模态模型，秘书自己生成台词+语音，字幕同步上镜。
        findViewById<Button>(R.id.btnTestAgent).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "secretary_react", phase = "intake",
                    parcelNo = 12, weight = 620, dest = "District 7", due = 1.50
                )
            )
        }
        // 单测 NPC 出场：左侧显 NPC 头像（assets/npc）+ 中间偏右打字机台词，按图片文件名循环播 people 音效。
        findViewById<Button>(R.id.btnTestNpc).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "npc_line",
                    title = "办事居民",
                    text = "你好，我来办理一项业务，以下是我的相关材料证明。",
                    gender = "male", age = "average"
                )
            )
        }
        // 单测能力①+场景①串联：先 daybrief 后台摘要谈资 → 紧跟 briefing_chat 阴阳开场。
        // 因 daybrief 摘要需网络往返，间隔 ~2.5s 再触发闲聊，给谈资缓存留就绪时间。
        findViewById<Button>(R.id.btnTestBriefingChat).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "secretary_daybrief", day = 5,
                    newspaper = listOf(
                        RealityEvent.NewsItem("第七码头供水部分恢复", "仍有多户居民报断水"),
                        RealityEvent.NewsItem("医院急件积压引发居民投诉")
                    ),
                    decisions = listOf(
                        RealityEvent.DecisionRecord("water_permit_07", "饮水许可", "rejected", 4),
                        RealityEvent.DecisionRecord("supervisor_priority", "主管保障表", "approved", 4)
                    )
                )
            )
            statusHandler.postDelayed({
                bridge.handle(RealityEvent(type = "secretary_briefing_chat", day = 5))
            }, 2500)
        }
        // 单测场景②：选件评论（眼镜端随机心情）。可反复点验证防刷屏（后一次会打断前一次）。
        findViewById<Button>(R.id.btnTestPickComment).setOnClickListener {
            bridge.handle(
                RealityEvent(
                    type = "secretary_pick_comment",
                    formId = "hospital_urgent_07", title = "医院急件",
                    action = "add", remainingSlots = 2,
                    factHint = "未验收将触发死亡回执"
                )
            )
        }

        // 单测场景③：按住说话（push-to-talk）。按下=开眼镜麦克风录音，松开=转写并让秘书回话。
        // 正式链路由游戏端发 secretary_chat_start / secretary_chat_stop 事件，这里等价模拟。
        findViewById<Button>(R.id.btnTestChat).setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> bridge.handle(RealityEvent(type = "secretary_chat_start"))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    bridge.handle(RealityEvent(type = "secretary_chat_stop"))
                }
            }
            true
        }

        requestNeededPermissions()
        updateStatus()

        // App 一打开就自动启动 WebSocket 服务，游戏可直接连接（无需再手动点「启动服务」按钮）。
        // 按钮保留，用于需要时手动重启服务。
        startServer()

        // 开始定时刷新状态栏（连上热点后 IP 会自动出现）。
        statusHandler.post(statusRefresher)

        // 启动时把所有网卡打进日志，便于判断是否连上热点。
        logNetworkDiagnostics()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startServer() {
        if (server != null) {
            log("服务已在运行")
            return
        }
        try {
            server = GameEventServer(
                port = wsPort,
                onEvent = { event -> runOnUiThread { bridge.handle(event) } },
                onLog = { msg -> runOnUiThread { log(msg) } }
            ).apply {
                isReuseAddr = true
                start()
            }
            updateStatus()
        } catch (e: Exception) {
            log("启动服务失败：${e.message}")
        }
    }

    private fun updateStatus() {
        val ip = localIpAddress()
        statusView.text = if (ip != null) {
            "游戏连接地址：ws://$ip:$wsPort\n（游戏机需与本机连同一热点/Wi-Fi）"
        } else {
            "❗本机未连 Wi-Fi/热点，拿不到局域网 IP。\n请先把本机连到与电脑相同的热点，IP 会自动显示。"
        }
    }

    private fun localIpAddress(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
            // 优先站点内网地址（10.* / 172.16-31.* / 192.168.*，含手机热点 172.20.10.*）
            (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /** 网络诊断：把所有网卡与 IPv4 地址打进日志，便于判断是否连上热点。 */
    private fun logNetworkDiagnostics() {
        try {
            val sb = StringBuilder("网络诊断（所有网卡）：")
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                val v4 = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .joinToString(", ") { it.hostAddress ?: "?" }
                sb.append("\n  · ${nif.name} up=${nif.isUp} v4=[$v4]")
            }
            log(sb.toString())
        } catch (e: Exception) {
            log("网络诊断失败：${e.message}")
        }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$ts] $msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusRefresher)
        server?.let { runCatching { it.stop() } }
        bridge.release()
        super.onDestroy()
    }
}
