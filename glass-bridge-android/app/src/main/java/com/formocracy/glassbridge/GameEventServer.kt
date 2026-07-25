package com.formocracy.glassbridge

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * 本地 WebSocket 服务：接收 Godot 游戏发来的现实事件。
 *
 * 游戏（Web 或 PC 原生）连到 手机IP:PORT，发送 RealityEvent 的 JSON 文本即可。
 * 手机与游戏机需在同一局域网（同一 Wi-Fi）。
 */
class GameEventServer(
    port: Int,
    private val onEvent: (RealityEvent) -> Unit,
    private val onLog: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private val gson = Gson()

    override fun onStart() {
        onLog("WebSocket 服务已启动，端口 = $port")
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        onLog("游戏已连接：${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        onLog("游戏断开：$reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val event = gson.fromJson(message, RealityEvent::class.java)
            if (event?.type == null) {
                onLog("忽略无效事件：$message")
                return
            }
            onLog("收到事件：${event.type}")
            onEvent(event)
        } catch (e: JsonSyntaxException) {
            onLog("JSON 解析失败：${e.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        onLog("WebSocket 错误：${ex.message}")
    }
}
