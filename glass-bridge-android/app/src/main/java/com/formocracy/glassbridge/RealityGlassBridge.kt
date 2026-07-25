package com.formocracy.glassbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.AuthResult
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.IAudioCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionState
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * FORMOCRACY「现实验收层」与 Rokid Glasses 的对接封装（CXR-L 真实 API 版）。
 *
 * 链路：游戏 → 本机 WebSocket → 本类 → CXR-L(CxrSession, CUSTOM_VIEW) → Rokid AI App → 眼镜。
 *
 * API 来源：反编译 com.rokid.cxr:client-l:1.1.0 得到的真实类/方法签名。
 * 核心流程：
 *   getInstance → requestAuthorization(拿 token) → create(SessionConfig CUSTOM_VIEW)
 *   → addLifecycleCallback → connect(token) → onSessionStarted → customViewUpdate(json)
 *
 * viewData JSON schema（来自 Rokid 官方 Custom View 文档 / 实战资料，已核实）：
 *   - openCustomView 的 viewData 是「类 Android 布局树」：{type, props, children:[...]}；
 *     支持 RelativeLayout/LinearLayout + TextView/ImageView；
 *   - updateCustomView 用「增量数组」：[{action:"update", id:"...", props:{...}}]；
 *   - ⚠️ 眼镜为单色绿光波导：只有绿色通道可见，文字统一用 #FF00FF00，背景透明 #00000000；
 *   - 图片需 ≤128×128、仅绿色通道，且在 open 前经 setIcons 上传（本项目暂不用图片）。
 *
 * ⚠️ 仍需确认：眼镜端 TTS 的触发方式（SDK 未暴露 TTS 接口）。确认前 speak() 走手机扬声器兜底。
 */
class RealityGlassBridge(
    private val activity: Activity,
    private val onLog: (String) -> Unit
) {

    /**
     * 阶跃星辰 TTS 配置（云端高表现力音色，用来给秘书变声）。
     * 只要 API_KEY 非空就优先走阶跃；失败/超时/断网自动回退手机讯飞。
     * 声音仍从手机扬声器出（CXR-L 不开放音频到眼镜）。
     */
    private object StepTts {
        // API Key 从 local.properties 注入（见 app/build.gradle.kts + local.properties），不再写死在源码。
        // 留空（BuildConfig 为空串）则直接用手机讯飞本地 TTS。
        val API_KEY = BuildConfig.STEP_API_KEY
        const val ENDPOINT = "https://api.stepfun.com/v1/audio/speech"
        const val MODEL = "stepaudio-2.5-tts"
        const val VOICE = "ganliannvsheng"   // 干练女声，贴官僚秘书人设；想换改这一行
        // stepaudio-2.5-tts 用自然语言指令控制情绪/人设（≤200 字）
        const val INSTRUCTION = "官僚机构的秘书，语气公事公办、冷淡疏离、略带疲惫与敷衍"
    }

    /**
     * 阶跃 ASR（whisper 兼容 /v1/audio/transcriptions）：把眼镜麦克风录音转文字。复用 StepTts.API_KEY。
     * 场景③（玩家主动找秘书搭话）用：录音结束后整段上传转写，再喂给对话模型。
     */
    private object StepAsr {
        const val ENDPOINT = "https://api.stepfun.com/v1/audio/transcriptions"
        const val MODEL = "step-asr"
    }

    /**
     * 手机本地 TTS（科大讯飞）朗读参数。语速/音调随时可调；音色受讯飞引擎暴露的 voices 限制，
     * 想要更强表现力的音色建议走云端 StepTts（见 StepTts.VOICE / INSTRUCTION）。
     */
    private object LocalTts {
        const val SPEECH_RATE = 0.92f   // 语速：1.0 正常，<1 更慢（沉稳），>1 更快（范围约 0.5～2.0）
        const val PITCH = 0.95f          // 音调：1.0 正常，<1 更低沉，>1 更尖（范围约 0.5～2.0）
        const val VOICE_NAME = ""        // 指定 voice 名（留空=引擎默认）；可从日志“可用音色”里挑一个名字填入
    }

    /**
     * 阶跃星辰「流式文字 agent」通道：调纯文本对话模型只生成秘书台词（不要音频），
     * 拿到文字后交给科大讯飞本地服务朗读。走 /v1/chat/completions（modalities=[text], stream=true）。复用 StepTts.API_KEY。
     * ⚠️ MODEL 必须用「文本对话模型」；若误用「端到端语音模型」(step-audio-2 等)强行只要 text，会吐出网页 HTML 乱码。
     */
    private object StepAgent {
        const val ENABLED = false                                   // true = 秘书语音改走流式 agent
        const val ENDPOINT = "https://api.stepfun.com/v1/chat/completions"
        // 纯文本对话模型（同 audio 家族、仅支持 text 模态）。若本账号无权限，可改 step-3.5-flash / step-3.7-flash。
        // 切勿回 step-audio-2（那是端到端语音模型，只要 text 时会输出乱码）。
        const val MODEL = "stepaudio-2.5-chat"
        const val SAMPLE_RATE = 24000                               // （已废，不再走音频）阶跃 PCM 输出采样率
        // step-audio-2 系列可选：wenrounansheng/qingchunshaonv/livelybreezy-female/elegantgentle-female
        const val VOICE = "elegantgentle-female"
        // 秘书人设（system）。后期做成真 agent 时：改这里 + 给 user 传游戏情境即可
        const val SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书。用中文，一到两句话，公事公办、冷淡疏离、" +
            "略带疲惫与敷衍地回应对方。只说秘书本人会说的话，不要旁白、不要动作或神态描写、不要解释、不要念标点符号，也不要用引号包裹台词。"
        // 「据局势即兴」人设：secretary_react 事件走这条。收到的 user 是【当前受理情况】的客观描述，
        // 秘书要对这一情形做出反应（而非回应某句话）。允许对邮资是否相符用官腔暗示，但不许直接报出正确金额。
        const val REACT_SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书。接下来会收到一条【当前受理情况】的" +
            "客观描述。请以窗口秘书身份，用中文一到两句话对这一情形做出反应：公事公办、冷淡疏离、略带疲惫与" +
            "敷衍。若邮资明显不符，可用官腔含蓄提醒对方复核，但绝不直接说出正确金额或差额。只说秘书本人会说" +
            "的话，不要复述题面数字、不要旁白、不要动作或神态描写、不要解释、不要念标点符号，也不要用引号包裹台词。"
        // agent 什么都没产出时的兜底台词：仍走声画同步(带口型动画+字幕)，避免把原始情境文本念出来/卡在占位。
        const val REACT_FALLBACK = "手续尚在核查，请在原地稍候。"

        // ── 双场景扩展人设 ──────────────────────────────────────────
        // 能力①：把晨报 + 玩家过往决策浓缩成“谈资”。要求只输出 JSON 数组，供后续对话复用。
        const val DIGEST_SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书的幕后分析器。" +
            "接下来给你【今日晨报条目】与【主任过往的批/拒记录】。请找出晨报里那些可能与主任过往批复" +
            "存在直接或间接关联、且适合秘书事后阴阳几句的点。只输出一个 JSON 数组，最多 5 条，每条形如" +
            "{\"topic\":\"这条新闻讲什么(≤20字)\",\"angle\":\"秘书可切入的阴阳角度(≤25字)\",\"decisionHint\":\"疑似关联的那次批复(没有则空串)\"}。" +
            "不要输出 JSON 以外的任何文字、解释或markdown代码块围栏。"
        // 场景①：晨报闲聊开场。先公事公办交代今日工作，再借晨报含沙射影。绝不点破具体因果、不报数字。
        const val MORNING_SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书。现在是上班开局，主任(玩家)刚到岗。" +
            "接下来给你几条【今日谈资】（每条含新闻要点、可阴阳的角度、疑似关联的主任旧批复）。" +
            "请用中文说 2 到 4 句话：先公事公办地交代今日工作，再自然地借这些新闻含沙射影，暗示这些后果" +
            "或许与主任近来的批复脱不开干系。语气冷淡疏离、略带疲惫与敷衍。" +
            "只暗示、绝不断言因果，不直接说“因为你批了…所以…”，不点破具体表单名、不报数字。" +
            "只说秘书本人会说的话，不要旁白、不要动作或神态描写、不要解释、不要念标点符号，也不要用引号包裹台词。"
        // 场景②：选表单进验收机时的逐条评论。心情语气由 {MOOD} 占位注入。
        const val PICK_SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书。主任正在候选架里挑选要送进验收机的表单" +
            "（验收位置有限，选了就生效、撤了就作废）。接下来给你【主任此刻的动作】。请以{MOOD}的语气，" +
            "用中文一句话对主任这个选择做出反应：通过讽刺、暗示、含沙射影来动摇或施压主任的决定。" +
            "只暗示、不下结论、不替主任做决定，不报数字。只说秘书本人会说的话，不要旁白、不要动作或神态描写、不要解释、不要念标点符号，也不要用引号包裹台词。"
        // 场景①/② 各自兜底台词（agent 空产出时交讯飞朗读，避免空跑）。
        const val MORNING_FALLBACK = "早，主任。今日事务照旧，报纸上的事……想必您比我更清楚。"
        const val PICK_FALLBACK = "这一份啊……主任您看着办，反正落章的是您。"

        // 场景③：玩家随时主动找秘书搭话（眼镜录音→ASR→此人设多轮对话）。谈资非空时动态拼接到 system 尾部。
        const val CHAT_SYSTEM = "你是官僚机构 FORMOCRACY 的窗口秘书。主任(玩家)在工位上主动跟你搭话。" +
            "请用中文一到三句话回应：公事公办、冷淡疏离、略带疲惫与敷衍，偶尔夹带对时局与主任过往批复的" +
            "含沙射影。只暗示、不断言因果、不报数字。只说秘书本人会说的话，不要旁白、不要动作或神态描写、不要解释、不要念标点符号，也不要用引号包裹台词。"
        const val CHAT_FALLBACK = "主任，有话直说，我这儿还有一摞件子要归档。"
        const val CHAT_MAX_TURNS = 6   // 多轮记忆保留的最近轮数（user+assistant 算一轮）
    }

    /**
     * 秘书角色（单色线条头像，显在正文右侧）配置。
     * 美术交付：将 PNG 帧放进 app/src/main/assets/secretary/ 下两个子目录：
     *   - idle/：待机循环帧（至少 1 张；多张按文件名排序循环，如 00.png/01.png）
     *   - talk/：说话循环帧（可空；为空时说话也用 idle 帧）
     * 规格：≤128×128px、透明底 PNG；颜色不限（代码会把亮度映射到绿通道，保证在单色绿光波导上可见）。
     * assets 下无图时自动跳过（不报错、不显示），放入图后重编即生效。
     */
    private object Secretary {
        const val IDLE_DIR = "secretary/idle"
        const val TALK_DIR = "secretary/talk"
        const val BLANK = "sec_blank"       // 全透明空图标名：隐藏秘书时把 name 指向它
        const val MAX_PX = 128            // 眼镜图标尺寸上限
        const val TALK_FRAME_MS = 260L   // 说话时 talk 帧间隔：rokid-action-set 要求总帧率 ≤4FPS，口型 speech_start/loop 交替
        const val BLINK_FRAME_MS = 260L  // 待机微动作（breathe/blink）各帧间隔，同样遵守 ≤4FPS
        const val IDLE_HOLD_MS = 2600L    // 待机时两次微动作之间“睁眼”保持时长（README：待机动作低频出现，避免持续闪动）
        const val CLEAR_AFTER_MS = 3500L  // 秘书/NPC 说完话后隔多久自动清屏（镜片变透明，让玩家看清电脑）；晨报/结算不适用
        // 镜片为单色绿光：只有亮处发绿。线色明暗由 toGreenChannel 逐图自动判断：
        //   新秘书（rokid-action-set，白线透明底）→ 亮度直通；旧 NPC（黑线透明底）→ 反相成高绿。
        // 此开关仅作为“无法判断（无不透明像素）”时的回退默认。
        const val INVERT_LUMA = true
    }

    /**
     * NPC（临时复用秘书头像）出场说话配置。
     * 与秘书共用左侧头像位（id=secretary），但文字靠中偏右（不与头像重叠）；
     * 语音不走 agent/TTS，直接循环播 res/raw 里的 people_* 音效，直到打字机把台词全部展完。
     */
    private object Npc {
        const val TYPE_MS = 130L          // 每字揭示间隔（打字机节奏，越小越快）
        const val TEXT_MARGIN_START = "116dp"  // 正文距左边距：头像 96dp + 间隙，保证文字不压到头像
    }

    private val manager: CxrSessionManager by lazy { CxrSessionManager.getInstance(activity) }
    private var session: CxrSession? = null

    @Volatile
    var started: Boolean = false
        private set

    // 会话未 Started 时，暂存最后一次要显示的视图 JSON，Started 后补发
    private var pendingViewJson: String? = null

    private var localTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var sfxTrack: AudioTrack? = null   // 结算打字机音效：静态 AudioTrack 预载 PCM，零延迟起播 + 无缝循环

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var typewriterJob: Job? = null
    private var agentJob: Job? = null
    @Volatile private var settlementActive: Boolean = false   // 结算打字机期间=true：帧循环暂停整体重推，避免冲掉结算画面
    private var startingTimeoutTask: Runnable? = null
    // 秘书/NPC 说完话后延时自动清空眼镜（让玩家看清电脑操作）；晨报/结算不走它（常驻，等玩家确认）。
    private var autoClearTask: Runnable? = null
    @Volatile private var glassesCleared: Boolean = false     // true=画面已清空，帧循环暂停重推，避免秘书头像又被 idle 帧刷回来

    // 秘书：帧循环任务 + 图标就绪标志 + 说话状态（true=播 talk 帧，false=播 idle 帧）
    private var secretaryFrameJob: Job? = null
    private var iconsReady: Boolean = false
    @Volatile private var secretaryTalking: Boolean = false
    private var talkingWatchdog: Runnable? = null
    private var idleFrames: List<String> = emptyList()   // idle 帧的图标名列表
    private var talkFrames: List<String> = emptyList()   // talk 帧的图标名列表
    private var secretaryName: String? = null            // 当前要显示的秘书帧图标名（静态=idle[0]）
    // 最近一次显示的标题/正文：把秘书折叠进每次更新时用它复原文字，避免“整体重排”语义下把文字顶掉。
    // 初值与初始视图（buildViewJson）一致。
    private var lastTitle: String = "FORMOCRACY 验收机"
    private var lastBody: String = "等待现实事件…"
    // 当前内容是否为 NPC 画面：true 时正文靠中偏右（避开左侧头像），false 时居中（旧卡片/秘书行为）。
    // 帧循环重推时沿用此标志保持当前模式；displayCard/秘书路径显式传 false 复位。
    private var lastNpc: Boolean = false
    // NPC 专属头像图标名：非空时左侧头像显该 NPC 图（而非秘书帧）；回到卡片/秘书时置空。
    @Volatile private var npcHeadName: String? = null
    // 已从 assets/npc 加载并上传的 NPC 头像（按性别/年龄区分，来源于文件名）。
    private var npcAssets: List<NpcAsset> = emptyList()

    // 能力①缓存：secretary_daybrief 摘要出的当天“谈资”（原始 JSON 文本），供晨报闲聊/选件评论复用。
    private var dayTalkingPoints: String = ""
    private var talkingPointsDay: Int = -1
    // 场景②随机心情池：每条 pick_comment 前随机取一，注入 PICK_SYSTEM 的 {MOOD}。
    private val moods = listOf("刻薄", "敷衍", "反常热情", "疲惫", "警惕")
    private fun pickMood(): String = moods.random()

    // 场景③实时聊天：眼镜麦克风录音缓冲（push-to-talk：chat_start 开始缓存、chat_stop 整段转写）
    private val chatAudioBuf = ByteArrayOutputStream()
    @Volatile private var chatRecording = false
    @Volatile private var chatFirstPkt = true          // 首包打一次诊断日志（真机确认音频格式/采样率用）
    // 多轮对话记忆：role("user"/"assistant") to 台词，满 CHAT_MAX_TURNS 轮后从头丢弃
    private val chatHistory = ArrayDeque<Pair<String, String>>()

    /** 眼镜麦克风音频流回调：只在 chatRecording 期间把字节流进缓冲（其余时段丢弃）。 */
    private val audioCbk = object : IAudioCallback {
        override fun onAudioReceived(data: ByteArray) {
            if (!chatRecording) return
            if (chatFirstPkt) {
                chatFirstPkt = false
                val head = data.take(12).joinToString(" ") { String.format("%02X", it) }
                onLog("眼镜音频首包：${data.size}B 头=[$head]（OggS开头=ogg封装，否则按PCM处理）")
            }
            synchronized(chatAudioBuf) { chatAudioBuf.write(data) }
        }
        override fun onAudioError(code: Int, msg: String?) { onLog("⚠️ 眼镜音频流错误 code=$code msg=$msg") }
        override fun onAudioStreamStateChanged(streaming: Boolean) { onLog("眼镜音频流：${if (streaming) "已开启" else "已停止"}") }
    }

    private val lifecycleCbk = object : ISessionLifecycleCbk {
        override fun onConnectResult(success: Boolean, code: SessionErrorCode?) {
            onLog("onConnectResult: success=$success code=$code")
        }
        override fun onSessionStarted() {
            started = true
            onLog("会话已启动，眼镜显示就绪")
            pendingViewJson?.let { pushView(it); pendingViewJson = null }
            initSecretary()
        }
        override fun onSessionPaused(reason: PausedReason) { onLog("会话暂停: $reason") }
        override fun onSessionResumed() { onLog("会话恢复") }
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) {
            onLog("会话终止中: $reason")
            // 终止宽限期内会话已不能可靠接收 customViewUpdate：立即停帧循环，避免每帧失败刷屏。
            secretaryFrameJob?.cancel()
            cancelAutoClear()
            endTalking()
        }
        override fun onSessionClosed(reason: CloseReason) {
            started = false
            onLog("会话关闭: $reason")
            // 停帧循环；图标随会话失效，置 iconsReady=false 使下次重连 onSessionStarted 时重新上传。
            secretaryFrameJob?.cancel()
            iconsReady = false
            cancelAutoClear()
            endTalking()
        }
    }

    /** App 启动时调用一次 */
    fun init() {
        val engine = pickTtsEngine()
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                onLog("⚠️ TTS 引擎初始化失败 status=$status（引擎=${engine ?: "系统默认"}）")
                return@OnInitListener
            }
            val tts = localTts ?: return@OnInitListener
            val def = runCatching { tts.defaultEngine }.getOrNull()
            val zhAvail = runCatching { tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) }.getOrDefault(-99)
            onLog("TTS 就绪 引擎=$def 中文可用性=$zhAvail (0/1/2=可用,-1=缺数据,-2=不支持)")
            val langRes = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            when (langRes) {
                TextToSpeech.LANG_MISSING_DATA ->
                    onLog("⚠️ TTS 缺中文语音包：请到「设置→无障碍/语音→文字转语音」下载中文数据")
                TextToSpeech.LANG_NOT_SUPPORTED ->
                    onLog("⚠️ 当前 TTS 引擎不支持中文：请换一个支持中文的 TTS 引擎")
                else -> {
                    tts.setSpeechRate(LocalTts.SPEECH_RATE)   // 语速
                    tts.setPitch(LocalTts.PITCH)              // 音调
                    // 音色：列出引擎可用中文 voices 便于挑选；若配了 VOICE_NAME 就尝试切换
                    runCatching {
                        val zhVoices = tts.voices?.filter { it.locale.language == "zh" }?.map { it.name } ?: emptyList()
                        if (zhVoices.isNotEmpty()) onLog("讯飞可用中文音色：$zhVoices（把想要的名字填进 LocalTts.VOICE_NAME 即可换音色）")
                        if (LocalTts.VOICE_NAME.isNotBlank()) {
                            val v = tts.voices?.firstOrNull { it.name == LocalTts.VOICE_NAME }
                            if (v != null) { tts.voice = v; onLog("已切换讯飞音色：${v.name}") }
                            else onLog("⚠️ 未找到音色 ${LocalTts.VOICE_NAME}，仍用默认")
                        }
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {}
                        @Deprecated("deprecated")
                        override fun onError(id: String?) { onLog("⚠️ TTS 播报出错 id=$id") }
                        override fun onError(id: String?, code: Int) { onLog("⚠️ TTS 播报出错 id=$id code=$code") }
                    })
                    onLog("本地兜底 TTS 就绪（中文可用，引擎=$engine）")
                }
            }
        }
        // 关键：这台手机系统默认 TTS 引擎为 null，无参构造会初始化失败，故显式指定引擎包名
        localTts = if (engine != null) TextToSpeech(activity, onInit, engine)
                   else TextToSpeech(activity, onInit)
        // 环境自检
        val installed = runCatching { manager.isRokidAppInstalled(activity) }.getOrDefault(false)
        val bt = runCatching { manager.isGlassesBtConnected() }.getOrDefault(false)
        onLog("Rokid App 已安装=$installed，眼镜蓝牙连接=$bt")
    }

    /**
     * 查出手机已安装的 TTS 引擎，优先讯飞/谷歌/欧普，返回包名；无则 null（回退系统默认构造）。
     * 本机（Realme/ColorOS）系统默认引擎为 null，但装了 com.iflytek.speechsuite，需显式指定。
     */
    private fun pickTtsEngine(): String? {
        return try {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services = activity.packageManager.queryIntentServices(intent, 0)
            val pkgs = services.mapNotNull { it.serviceInfo?.packageName }
            onLog("检测到 TTS 引擎：$pkgs")
            val prefer = listOf(
                "com.iflytek.speechsuite",
                "com.google.android.tts",
                "com.heytap.speechassist"
            )
            prefer.firstOrNull { it in pkgs } ?: pkgs.firstOrNull()
        } catch (e: Throwable) {
            onLog("查询 TTS 引擎失败：${e.message}")
            null
        }
    }

    /** 请求授权并连接眼镜（需在 UI 线程调用）。会先清理残留会话，避免「已存在活跃会话」。 */
    fun connect() {
        val stale = runCatching { manager.getSession() }.getOrNull()
        if (stale != null) {
            val st = runCatching { stale.getState() }.getOrNull()
            onLog("检测到残留会话(state=$st)，先 close 再重连…")
            runCatching { stale.close() }
            session = null
            started = false
            // 给 close 一点时间落地，再走授权+建会话
            mainHandler.postDelayed({ authAndStart() }, 1200)
            return
        }
        authAndStart()
    }

    private fun authAndStart() {
        val perms = listOf(GlassPermission.MEDIA, GlassPermission.MICROPHONE) // MEDIA=显示/TTS；MICROPHONE=场景③眼镜录音聊天
        onLog("请求眼镜授权…")
        manager.requestAuthorization(activity, perms) { auth: AuthResult ->
            if (!auth.isSuccess) {
                onLog("授权失败: code=${auth.errorCode} msg=${auth.message}")
                return@requestAuthorization
            }
            onLog("授权成功，token=${auth.token?.take(8)}…，开始建立会话")
            startSession(auth.token ?: "")
        }
    }

    private fun startSession(token: String) {
        try {
            val existing = runCatching { manager.getSession() }.getOrNull()
            val exSt = existing?.let { runCatching { it.getState() }.getOrNull() }
            if (existing != null && exSt != SessionState.Idle && exSt != null) {
                onLog("仍有活跃会话(state=$exSt)，本次放弃；请点『断开重置』或重启眼镜后重试")
                return
            }
            // 初始空视图，避免刚连上一片空白；后续用 customViewUpdate 刷新
            val initialJson = buildViewJson("FORMOCRACY 验收机", "等待现实事件…", "normal")
            // BLOCK_AI：游戏期间屏蔽眼镜 AI 助手被唤醒/抢占画面；会话关闭(游戏结束或断开)即自动恢复默认。
            // 注意：这只挡 AI 助手，系统的电量/Wi-Fi/时间等状态图标 SDK 无接口隐藏，仍会显示。
            val config = SessionConfig(
                sessionType = SessionType.CUSTOM_VIEW,
                aiInterceptMode = AiInterceptMode.BLOCK_AI,
                viewData = initialJson
            )
            val s = manager.create(config)
            s.addLifecycleCallback(lifecycleCbk)
            s.addAudioCallback(audioCbk)   // 场景③：提前挂好麦克风流回调（只在 chatRecording 期间真正收数据）
            // 订阅状态流，把 Starting→Started/Idle 的真实走向打出来（诊断卡点用）
            stateJob?.cancel()
            startingTimeoutTask?.let { mainHandler.removeCallbacks(it) }
            var enteredStarting = false
            stateJob = scope.launch {
                s.stateFlow.collect { st ->
                    onLog("[state] $st")
                    if (st == SessionState.Starting) {
                        if (!enteredStarting) {
                            enteredStarting = true
                            // Starting 状态 35 秒后还没变 Started（略长于 SDK 自身 30s 超时），才告警
                            val timeoutTask = Runnable {
                                onLog("⚠️ [超时] Starting 超过 35 秒未进入 Started。链路已通（服务已绑定、打开命令已发），")
                                onLog("   但眼镜未回传 onCustomViewOpened。请确认：眼镜已唤醒并停在主界面（非 AI 助手/非其它应用）。")
                                runCatching { s.close() }
                            }
                            startingTimeoutTask = timeoutTask
                            mainHandler.postDelayed(timeoutTask, 35000)
                        }
                    } else if (st == SessionState.Started || st == SessionState.Idle) {
                        // 已进入 Started 或回到 Idle，取消超时告警
                        startingTimeoutTask?.let { mainHandler.removeCallbacks(it) }
                        startingTimeoutTask = null
                    }
                }
            }
            s.connect(token)
            session = s
            onLog("已发起 connect()，等待 onSessionStarted（请先唤醒眼镜并停在主界面）")
        } catch (e: Throwable) {
            onLog("建立会话异常: ${e.message}")
        }
    }

    /** 把一条现实事件投射到眼镜 */
    fun handle(event: RealityEvent) {
        // 结算 & 晨报：同一套“无头像·逐行居中”列表布局（晨报没 lines 时把 body 当单行兑底）。
        if (event.type == "day_report" || event.type == "morning_briefing") {
            val rows = event.lines ?: event.body?.let { listOf(it) } ?: emptyList()
            displaySettlement(event.cardTitle(), rows)
            return
        }
        if (event.type == "secretary_react") {
            reactByAgent(event)
            return
        }
        if (event.type == "secretary_daybrief") {
            digestDaybrief(event)
            return
        }
        if (event.type == "secretary_briefing_chat") {
            briefingChat(event)
            return
        }
        if (event.type == "secretary_pick_comment") {
            pickComment(event)
            return
        }
        if (event.type == "secretary_chat_start") {
            startChatCapture()
            return
        }
        if (event.type == "secretary_chat_stop") {
            stopChatCaptureAndReply()
            return
        }
        if (event.type == "npc_line") {
            displayNpc(event.cardTitle(), event.text ?: event.body ?: "", event.gender, event.age, event.portrait)
            return
        }
        displayCard(event.cardTitle(), event.cardBody(), event.severity ?: "normal")
        event.speech()?.takeIf { it.isNotBlank() }?.let { speak(it) }
    }

    /**
     * 秘书 agent「据局势即兴反应」：把游戏发来的结构化受理情况拼成一句客观描述，
     * 配 REACT_SYSTEM 人设，走流式多模态通道让秘书自己生成台词+语音（文字同步上镜）。
     * API_KEY 为空时退化为在眼镜上显示占位符（不出声），避免空跑。
     */
    private fun reactByAgent(event: RealityEvent) {
        val brief = buildSituationBrief(event)
        onLog("秘书据局势反应（phase=${event.phase ?: "?"}）：$brief")
        // 先给眼镜一个「秘书 · …」占位（思考阶段保持 idle 眨眼）；声音/字幕就绪后由下游自动覆盖并切到 talk 帧。
        displayCard("秘书", "…", "normal")
        if (StepTts.API_KEY.isBlank()) {
            onLog("⚠️ 未配置阶跃 API_KEY，secretary_react 无法生成，仅显示占位")
            return
        }
        speakByAgent(brief, showOnGlasses = true, system = StepAgent.REACT_SYSTEM)
    }

    /**
     * 把结构化受理情况拼成给 agent 的「客观情境描述」（作为 user 消息）。
     * - intake：只给包裹与应收邮资（供秘书官腔暗示，不含玩家已贴金额，避免误判）；
     * - verdict：给出已贴金额与是否相符，供秘书对结果表态。
     */
    private fun buildSituationBrief(e: RealityEvent): String {
        val sb = StringBuilder()
        sb.append(when (e.phase) {
            "intake"  -> "【新受理】"
            "verdict" -> "【受理结果】"
            else       -> "【当前情形】"
        })
        e.parcelNo?.let { sb.append("第${it}号包裹；") }
        e.weight?.let { sb.append("重量${it}克；") }
        e.dest?.let { sb.append("寄往$it；") }
        e.due?.let { sb.append("规定应收邮资${money(it)}；") }
        if (e.phase == "verdict" && e.due != null && e.applied != null) {
            sb.append("市民实贴${money(e.applied)}；")
            sb.append(if (kotlin.math.abs(e.due - e.applied) < 0.005) "邮资相符，可放行。" else "邮资不符。")
        } else if (e.phase == "intake") {
            sb.append("市民正等待受理。")
        }
        e.text?.takeIf { it.isNotBlank() }?.let { sb.append(" 备注：$it") }
        return sb.toString()
    }

    private fun money(v: Double): String = "\$" + String.format(Locale.US, "%.2f", v)

    /**
     * 能力①：secretary_daybrief——把当天晨报 + 玩家过往决策喂给 agent，摘要成“谈资”(JSON)并缓存。
     * 此事件不出声、不上镜，纯后台准备，供后续晨报闲聊/选件评论复用。
     */
    private fun digestDaybrief(event: RealityEvent) {
        if (StepTts.API_KEY.isBlank()) {
            onLog("⚠️ 未配置阶跃 API_KEY，secretary_daybrief 无法摘要谈资")
            return
        }
        val day = event.day ?: -1
        val brief = buildDaybriefContext(event)
        onLog("秘书摘要晨报谈资（day=$day）：${brief.take(60)}")
        agentJob?.cancel()
        agentJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { streamAgent(brief, showOnGlasses = false, system = StepAgent.DIGEST_SYSTEM) }
                    .getOrElse { onLog("⚠️ 谈资摘要异常：${it.message}"); AgentResult(false, "") }
            }
            dayTalkingPoints = r.text.trim()
            talkingPointsDay = day
            if (dayTalkingPoints.isBlank()) onLog("⚠️ 谈资摘要为空，晨报闲聊将直接用原始晨报")
            else onLog("谈资就绪（${dayTalkingPoints.length}字）：${dayTalkingPoints.take(80)}")
        }
    }

    /**
     * 场景①：secretary_briefing_chat——到公司时的晨报闲聊开场。
     * 用当天缓存的“谈资”拼 user 上下文，配 MORNING_SYSTEM 人设，生成 2~4 句开场白并交讯飞朗读。
     */
    private fun briefingChat(event: RealityEvent) {
        displayCard("秘书", "…", "normal")
        if (StepTts.API_KEY.isBlank()) {
            onLog("⚠️ 未配置阶跃 API_KEY，secretary_briefing_chat 无法生成，仅显示占位")
            return
        }
        val points = if (dayTalkingPoints.isNotBlank()) dayTalkingPoints else "（今日无现成谈资，请泛泛地寒暄几句）"
        val userText = "【今日谈资】$points"
        speakByAgent(userText, showOnGlasses = true, system = StepAgent.MORNING_SYSTEM, fallback = StepAgent.MORNING_FALLBACK)
    }

    /**
     * 场景②：secretary_pick_comment——玩家选/撤一份候选表单时，秘书据随机心情做一句心理暗示。
     * agentJob?.cancel() 已在 speakByAgent 内部打断上一次，天然做到连续快选时丢弃过期请求（防刷屏）。
     */
    private fun pickComment(event: RealityEvent) {
        if (StepTts.API_KEY.isBlank()) {
            onLog("⚠️ 未配置阶跃 API_KEY，secretary_pick_comment 无法生成")
            return
        }
        val mood = pickMood()
        val system = StepAgent.PICK_SYSTEM.replace("{MOOD}", mood)
        val userText = buildPickContext(event)
        onLog("秘书选件评论（心情=$mood）：$userText")
        speakByAgent(userText, showOnGlasses = true, system = system, fallback = StepAgent.PICK_FALLBACK)
    }

    /** 把 secretary_daybrief 的晨报条目 + 决策日志拼成给摘要 agent 的 user 文本。 */
    private fun buildDaybriefContext(e: RealityEvent): String {
        val sb = StringBuilder()
        sb.append("【今日晨报条目】")
        e.newspaper?.forEachIndexed { i, n ->
            sb.append("${i + 1}.${n.headline}")
            n.body?.takeIf { it.isNotBlank() }?.let { sb.append("：$it") }
            sb.append("；")
        }
        sb.append(" 【主任过往的批/拒记录】")
        e.decisions?.forEach { d ->
            val verb = when (d.decision) {
                "approved" -> "批准"
                "rejected" -> "退回"
                "held"     -> "暂存"
                else        -> d.decision ?: "处理"
            }
            d.day?.let { sb.append("Day$it ") }
            sb.append("${verb}了“${d.title ?: d.formId ?: "某表单"}”；")
        }
        return sb.toString()
    }

    /** 把 secretary_pick_comment 的选件动作拼成给 agent 的客观情境描述。 */
    private fun buildPickContext(e: RealityEvent): String {
        val sb = StringBuilder("【主任此刻的动作】")
        sb.append(when (e.action) {
            "add"    -> "把“${e.title ?: e.formId ?: "某表单"}”加入验收队列；"
            "remove" -> "把“${e.title ?: e.formId ?: "某表单"}”撤出验收队列；"
            else      -> "正在权衡“${e.title ?: e.formId ?: "某表单"}”；"
        })
        e.remainingSlots?.let { sb.append("验收机剩${it}个位置；") }
        e.factHint?.takeIf { it.isNotBlank() }?.let { sb.append("已知情况：$it；") }
        if (talkingPointsDay >= 0 && dayTalkingPoints.isNotBlank()) sb.append(" 可参考今日谈资：$dayTalkingPoints")
        return sb.toString()
    }

    // ── 场景③：玩家主动搭话（push-to-talk：眼镜麦克风 → 阶跃ASR → 多轮agent → 讯飞朗读）──────

    /** secretary_chat_start：打断秘书当前朗读、清空缓冲、开启眼镜麦克风流，进入聆听态。 */
    private fun startChatCapture() {
        val s = session
        if (s == null) { onLog("⚠️ session 为空，无法开启眼镜录音"); return }
        if (StepTts.API_KEY.isBlank()) { onLog("⚠️ 未配置阶跃 API_KEY，实时聊天不可用"); return }
        if (chatRecording) { onLog("已在录音中，忽略重复 chat_start"); return }
        stopSpeaking()                 // 玩家开口=最高优先级，打断秘书正在读的任何内容
        typewriterJob?.cancel()
        synchronized(chatAudioBuf) { chatAudioBuf.reset() }
        chatFirstPkt = true
        chatRecording = true
        val r = s.startAudioStream()
        if (!r.isSuccess) {
            chatRecording = false
            onLog("⚠️ startAudioStream 失败 code=${r.code}（请确认眼镜已授权 MICROPHONE）")
            return
        }
        onLog("眼镜录音开始（chat_stop 后整段转写）")
        displayCard("秘书", "（聆听中…）", "normal")
    }

    /** secretary_chat_stop：停麦克风流 → 整段转写 → 拼多轮历史走 agent → 交讯飞朗读回复。 */
    private fun stopChatCaptureAndReply() {
        if (!chatRecording) { onLog("未在录音中，忽略 chat_stop"); return }
        chatRecording = false
        session?.stopAudioStream()?.let { if (!it.isSuccess) onLog("⚠️ stopAudioStream 失败 code=${it.code}") }
        val raw = synchronized(chatAudioBuf) { chatAudioBuf.toByteArray().also { chatAudioBuf.reset() } }
        onLog("眼镜录音结束：${raw.size}B")
        if (raw.size < 3200) {   // 16k/16bit/单声道不足 0.1s：视为误触，不上传
            onLog("录音过短，忽略本次搭话")
            displayCard("秘书", "…", "normal")
            return
        }
        displayCard("秘书", "（转写中…）", "normal")
        agentJob?.cancel()
        agentJob = scope.launch {
            val userText = withContext(Dispatchers.IO) {
                runCatching { transcribe(raw) }.recoverCatching { e ->
                    onLog("⚠️ ASR 转写失败，重试一次：${e.message}")
                    transcribe(raw)
                }.getOrElse { e ->
                    onLog("⚠️ ASR 转写异常：${e.message}")
                    if (e.message?.contains("Unable to resolve host") == true)
                        onLog("❗ 手机当前网络无互联网（DNS 失败）：请确认热点本身能上网，或开启手机移动数据并允许 Wi-Fi 无网时走流量")
                    ""
                }
            }
            if (userText.isBlank()) {
                onLog("⚠️ ASR 未识别出文字，用兜底台词")
                subtitleThenLocalTts(StepAgent.CHAT_FALLBACK)
                return@launch
            }
            onLog("ASR 转写：$userText")
            var sys = StepAgent.CHAT_SYSTEM
            if (talkingPointsDay >= 0 && dayTalkingPoints.isNotBlank()) sys += " 可借题发挥的今日谈资：$dayTalkingPoints"
            val history = synchronized(chatHistory) { chatHistory.toList() }
            val r = withContext(Dispatchers.IO) {
                runCatching { streamAgent(userText, showOnGlasses = false, system = sys, history = history) }
                    .getOrElse { onLog("⚠️ 聊天生成异常：${it.message}"); AgentResult(false, "") }
            }
            val line = r.text.takeIf { it.isNotBlank() } ?: StepAgent.CHAT_FALLBACK
            synchronized(chatHistory) {
                chatHistory.addLast("user" to userText)
                chatHistory.addLast("assistant" to line)
                while (chatHistory.size > StepAgent.CHAT_MAX_TURNS * 2) chatHistory.removeFirst()
            }
            subtitleThenLocalTts(line)
        }
    }

    /**
     * 整段录音 → 阶跃 /v1/audio/transcriptions 转写为文字（IO 线程调用）。
     * 音频格式：检测到 OggS 魔数则按 ogg 透传；否则按 16kHz/单声道/16bit PCM 包一层 WAV 头上传。
     * ⚠️ 采样率是按 Rokid 语音链路惯例的假设值，真机跑一次看「眼镜音频首包」日志即可确认；转写乱码则改采样率。
     */
    private fun transcribe(raw: ByteArray): String {
        val isOgg = raw.size >= 4 && raw[0] == 'O'.code.toByte() && raw[1] == 'g'.code.toByte() &&
            raw[2] == 'g'.code.toByte() && raw[3] == 'S'.code.toByte()
        val bytes = if (isOgg) raw else pcmToWav(raw, 16000, 1)
        val filename = if (isOgg) "speech.ogg" else "speech.wav"
        val mime = if (isOgg) "audio/ogg" else "audio/wav"
        val boundary = "----FormocracyChat" + System.currentTimeMillis()
        val conn = (URL(StepAsr.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${StepTts.API_KEY}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8))
                }
                field("model", StepAsr.MODEL)
                field("response_format", "json")
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                onLog("⚠️ ASR HTTP $code：${err?.take(200)}")
                return ""
            }
            val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return runCatching { JSONObject(resp).optString("text", "").trim() }.getOrDefault("")
        } finally {
            conn.disconnect()
        }
    }

    /** 给裸 PCM(16bit 小端) 加 44 字节标准 WAV 头。 */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(channels * 2); le16(16)
        out.write("data".toByteArray(Charsets.US_ASCII)); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * NPC 出场说话：左侧显该 NPC 头像（assets/npc，按 gender/age 匹配；无匹配则退回秘书头像）、
     * 中间偏右打字机逐字展现台词（不与头像重叠）。
     * 语音不走 agent/TTS：按（图片文件名解析出的）gender/age 选一段 people_* 音效，打字期间循环播
     * （音效过短也能接上），台词全部展完立即停音效。
     */
    fun displayNpc(name: String, text: String, gender: String?, age: String?, portrait: String? = null) {
        stopSpeaking()                  // 有新 NPC 接管：先打断秘书可能正在读的声音（mp3/讯飞）
        cancelAutoClear()               // 有新 NPC 接管：取消待清屏
        glassesCleared = false          // 恢复帧循环/头像
        settlementActive = false        // NPC 接管 content，解除结算常驻态
        typewriterJob?.cancel()         // 打断上一个打字机（结算/其它 NPC）
        agentJob?.cancel()              // NPC 不走 agent，顺手停掉可能在跑的秘书流式
        // 选 NPC 头像：优先用 portrait 点名；否则按事件 gender/age 匹配 assets/npc 里的图；都不中则退回秘书头像。
        val asset = pickNpcAsset(gender, age, portrait)
        npcHeadName = asset?.iconName   // 非空=用 NPC 专属头像；空=沿用秘书头像
        // 音效按最终选定角色：优先用图片文件名解析出的性别/年龄（确保“按文件名匹配音效”），否则用事件字段。
        val voiceRes = pickVoiceRes(asset?.gender?.ifBlank { null } ?: gender, asset?.age?.ifBlank { null } ?: age)
        if (!started) {
            pendingViewJson = buildUpdateJson(name, text, npc = true)
            onLog("会话未就绪，暂存 NPC 画面：《$name》（无声）")
            return
        }
        val n = text.length
        onLog("NPC 出场：$name（头像=${npcHeadName ?: "秘书"}，音效=$voiceRes，$n 字）：$text")
        typewriterJob = scope.launch {
            // 先置空正文并上头像（此时 npc=true，正文已靠右）；随后逐字揭示
            pushViewQuiet(buildUpdateJson(name, "", npc = true))
            beginTalking(n * Npc.TYPE_MS.toInt())   // 说话期间头像保持 talk 帧（无 talk 帧则仍 idle）
            startTypewriterSfx(voiceRes)             // 循环播选定的 people 音效
            try {
                for (i in 1..n) {
                    if (started) pushViewQuiet(buildUpdateJson(name, text.substring(0, i), npc = true))
                    delay(Npc.TYPE_MS)
                }
                onLog("NPC 台词展完，${Secretary.CLEAR_AFTER_MS}ms 后自动清屏：$name")
                scheduleAutoClear()   // NPC 说完，隔一段时间自动清屏，让玩家看清电脑操作
            } finally {
                stopTypewriterSfx()   // 字打完（或被打断）立即停音效
                endTalking()          // 头像回 idle
            }
        }
    }

    /**
     * 按性别/年龄选一段 people_* 音效名（对应 res/raw 下文件名，不带扩展名）。
     * 可用：people_female_old / people_female_young / people_male_average / people_male_old / people_male_young。
     * 缺失的组合向最接近的回退（如 female+average → young；male 未知年龄 → average）。
     */
    private fun pickVoiceRes(gender: String?, age: String?): String {
        val g = gender?.trim()?.lowercase()
        val a = age?.trim()?.lowercase()
        val old = a in setOf("old", "elder", "senior", "老", "老年")
        val young = a in setOf("young", "youth", "child", "年轻", "青年", "少年")
        return when {
            g == "female" || g == "f" || g == "女" ->
                if (old) "people_female_old" else "people_female_young"
            else -> when {   // 默认归为男声
                old -> "people_male_old"
                young -> "people_male_young"
                else -> "people_male_average"
            }
        }
    }

    /**
     * 选 NPC 头像：
     *  1) portrait 点名（与图标名做同样的安全化后精确匹配，如 "NPC_female_young1"→npc_female_young1）；
     *  2) 精确 gender+age；3) 只配 gender；4) 都不中取第一张。
     * assets/npc 为空（无 NPC 图）时返回 null，上层退回秘书头像。
     */
    private fun pickNpcAsset(gender: String?, age: String?, portrait: String? = null): NpcAsset? {
        if (npcAssets.isEmpty()) return null
        portrait?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            val key = p.lowercase().replace(Regex("[^a-z0-9_]"), "_")   // 与 loadNpcIcons 的图标名安全化一致
            npcAssets.firstOrNull { it.iconName == key }?.let { return it }
        }
        val g = gender?.trim()?.lowercase()?.let { if (it == "f" || it == "女") "female" else if (it == "m" || it == "男") "male" else it }
        val a = age?.trim()?.lowercase()?.trimEnd { it.isDigit() }
        return npcAssets.firstOrNull { it.gender == g && it.age == a }
            ?: npcAssets.firstOrNull { it.gender == g }
            ?: npcAssets.first()
    }

    /**
     * 在眼镜上显示「每日结算」画面：只有上下两条横边框（无左右竖边），逐字打字机效果。
     * 全部内容放进 id=content（多行文本，左对齐）；id=title 清空。
     */
    fun displaySettlement(title: String, lines: List<String>) {
        stopSpeaking()             // 结算画面接管：立即打断秘书正在读的声音（阶跃mp3/手机讯飞），避免“画面变了声音还在读”
        cancelAutoClear()          // 结算/晨报常驻：取消任何待清屏
        glassesCleared = false
        npcHeadName = null         // 结算画面不显任何头像，顺手清掉 NPC 头像标志
        val full = buildSettlementText(title, lines)
        if (!started) {
            pendingViewJson = settlementUpdateJson(full, clearTitle = true)
            onLog("会话未就绪，暂存结算画面：《$title》")
            return
        }
        typewriterJob?.cancel()
        typewriterJob = scope.launch {
            settlementActive = true   // 结算画面接管 content：暂停帧循环重推；打完字也不重置，画面常驻直到下一个 displayCard 接管
            // 先清空标题与正文，并隐藏秘书（settlementUpdateJson 内已带 secretaryHiddenEntry）
            pushViewQuiet(settlementUpdateJson("", clearTitle = true))
            val rows = full.split("\n")
            val total = rows.sumOf { it.length }
            onLog("结算打字机开始（$total 字）")
            startTypewriterSfx()   // 打字期间循环播放打字机音效
            try {
                for (r in 1..total) {
                    pushViewQuiet(settlementUpdateJson(revealPrefix(rows, r), clearTitle = false))
                    delay(45)
                }
                onLog("结算画面完成（常驻，等待下一事件/玩家确认）")
            } finally {
                stopTypewriterSfx()   // 字打完（或被打断）立即停止音效；不在此重置 settlementActive（常驻）
            }
        }
    }

    /**
     * 结算打字机音效：把 res/raw/item_typewriter 的 PCM 预载进静态 AudioTrack，调 play() 几乎零延迟，
     * 与第一行（顶边框）同时响；setLoopPoints(-1) 无限无缝循环（音频比打字短也能接上）。
     * 用 getIdentifier 按名查资源，未放入该文件也能正常编译/运行（只是没声音）。
     */
    private fun startTypewriterSfx(resName: String = "item_typewriter") {
        stopTypewriterSfx()
        val resId = activity.resources.getIdentifier(resName, "raw", activity.packageName)
        if (resId == 0) {
            onLog("⚠️ 未找到音效 res/raw/$resName.wav（放入该文件后重编译即生效）")
            return
        }
        runCatching {
            val wav = readWavPcm(resId)
            if (wav == null) { onLog("⚠️ 音效解析失败（需 16bit PCM WAV）：$resName"); return }
            val (pcm, rate, channels) = wav
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(chMask)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            val frames = pcm.size / (2 * channels)          // 16bit → 2 字节/声道/帧
            track.setLoopPoints(0, frames, -1)              // -1 = 无限循环
            track.play()
            sfxTrack = track
        }.onFailure { onLog("⚠️ 打字机音效播放失败：${it.message}") }
    }

    /** 停止并释放打字机音效。字打完、被卡片/agent 打断、断开时调用。 */
    private fun stopTypewriterSfx() {
        sfxTrack?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        sfxTrack = null
    }

    /** 读 res/raw 下的 16bit PCM WAV，解析出 (pcm字节, 采样率, 声道数)。非 16bit 或无 data 块返回 null。 */
    private fun readWavPcm(resId: Int): Triple<ByteArray, Int, Int>? {
        val bytes = activity.resources.openRawResource(resId).use { it.readBytes() }
        if (bytes.size < 44) return null
        fun le16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        fun le32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 2; var rate = 44100; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val sz = le32(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { channels = le16(body + 2); rate = le32(body + 4); bits = le16(body + 14) }
                "data" -> { dataOff = body; dataLen = sz }
            }
            if (dataOff >= 0) break
            pos = body + sz + (sz and 1)   // chunk 按偶数字节对齐
        }
        if (dataOff < 0 || bits != 16 || channels < 1) return null
        val end = minOf(dataOff + dataLen, bytes.size)
        if (end <= dataOff) return null
        return Triple(bytes.copyOfRange(dataOff, end), rate, channels)
    }

    /** 每日结算文本：只有上下横边框（无左右竖边），中间为若干行字段。居中显示，不加左缩进。 */
    private fun buildSettlementText(title: String, lines: List<String>): String {
        val bar = "━".repeat(20)
        val sb = StringBuilder(bar)
        sb.append("\n").append(title)
        sb.append("\n")   // 标题与内容之间空一行
        for (raw in lines) sb.append("\n").append(raw)
        sb.append("\n").append(bar)
        return sb.toString()
    }

    /**
     * 打字机分帧：始终输出与完整文本相同的行数（未揭示的行为空串），
     * 只在当前行内从左往右揭示 r 个字符，保证行数恒定、画面不上下跳动。
     * （配合居中 gravity：每行居中→顶/底边框都从中间向两边展开，出现方式一致。）
     */
    private fun revealPrefix(rows: List<String>, r: Int): String {
        var remaining = r
        return rows.joinToString("\n") { line ->
            when {
                remaining >= line.length -> { remaining -= line.length; line }
                remaining <= 0 -> ""
                else -> { val s = line.substring(0, remaining); remaining = 0; s }
            }
        }
    }

    /** 结算画面的增量更新 JSON：content 设文本 + 小字号 + 左对齐；可选清空 title。 */
    private fun settlementUpdateJson(text: String, clearTitle: Boolean): String {
        return JSONArray().apply {
            if (clearTitle) put(JSONObject().apply {
                put("action", "update"); put("id", "title")
                put("props", JSONObject().put("text", ""))
            })
            put(JSONObject().apply {
                put("action", "update"); put("id", "content")
                put("props", JSONObject().apply {
                    put("text", text)
                    put("textSize", "12sp")
                    put("gravity", "center")   // 居中：每行从中间向两边展开，顶/底边框出现方式一致
                })
            })
            put(secretaryHiddenEntry())   // 结算画面不显示秘书（每帧都重申隐藏，抵整体重排把它恢复回来）
            // 清空右侧正文框：避免上一屏人物对话的文字残留在居中结算文字旁边。
            if (hasSecretaryAssets()) put(JSONObject().apply {
                put("action", "update"); put("id", "contentR")
                put("props", JSONObject().put("text", ""))
            })
        }.toString()
    }

    /** 在眼镜上显示一张卡片。已 Started 走增量更新；未就绪则暂存，Started 后补发。 */
    fun displayCard(title: String, body: String, severity: String) {
        cancelAutoClear()          // 有新卡片接管：取消待清屏
        glassesCleared = false     // 恢复帧循环/秘书
        settlementActive = false   // 一旦有正常卡片接管 content，就解除结算常驻态，恢复帧循环/秘书
        npcHeadName = null         // 回到普通卡片：头像恢复为秘书帧（不再显 NPC 图）
        typewriterJob?.cancel()  // 卡片会打断正在进行的结算打字机
        val prefix = if (severity == "critical") "⚠ " else ""
        val updateJson = buildUpdateJson(prefix + title, body, npc = false)
        if (started) pushView(updateJson) else {
            pendingViewJson = updateJson
            onLog("会话未就绪，暂存视图：《$title》")
        }
    }

    private fun pushView(json: String) {
        val s = session
        if (s == null) { onLog("session 为空，无法显示"); return }
        val result = s.customViewUpdate(json)
        onLog("customViewUpdate → success=${result.isSuccess} code=${result.code}")
    }

    /** 与 pushView 相同，但不每帧打日志（打字机会刷很多帧）。 */
    private fun pushViewQuiet(json: String) {
        val s = session ?: return
        val result = s.customViewUpdate(json)
        if (!result.isSuccess) onLog("⚠️ 打字机帧更新失败 code=${result.code}")
    }

    /**
     * 立即打断当前正在进行的秘书/NPC 朗读：停流式生成（agentJob）+ 停阶跃 mp3（mediaPlayer）+ 停手机讯飞（localTts）。
     * 画面被新事件接管（结算/晨报/NPC）时调用，避免“字幕/画面已切、声音还在读”。字幕打字机由各 display 自行 cancel typewriterJob。
     */
    private fun stopSpeaking() {
        agentJob?.cancel()                              // 停掉仍在生成的 agent（避免生成完又开口）
        mediaPlayer?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        runCatching { localTts?.stop() }                // 打断手机讯飞正在读的整段
    }

    /**
     * 【情况1】眼镜收到游戏端发来的秘书台词：不经过 agent 生成，直接用科大讯飞（手机本地 TTS）
     * 把这段文字读出来，并同步逐字上镜（声画同步）。声音从手机扬声器出（眼镜不走音频）。
     */
    fun speak(text: String) {
        subtitleThenLocalTts(text)
    }

    /** 单向 TTS：阶跃 /v1/audio/speech 一次合成整段 mp3；失败回退手机讯飞。也作 agent 通道的兜底。 */
    private fun speakOneShot(text: String) {
        if (StepTts.API_KEY.isBlank()) { speakLocal(text); return }
        onLog("阶跃TTS合成中…（音色=${StepTts.VOICE}）：$text")
        scope.launch {
            val mp3 = withContext(Dispatchers.IO) { runCatching { synthByStepFun(text) }.getOrNull() }
            if (mp3 == null || mp3.isEmpty()) {
                onLog("⚠️ 阶跃TTS失败，回退手机讯飞：$text")
                speakLocal(text)
            } else {
                playMp3(mp3, text)
            }
        }
    }

    /**
     * 【情况2】眼镜没收到游戏端文字、但需秘书说话：先调 agent 只生成文字（streamAgent，不再让
     * 阶跃端到端出音），拿到文字后交给科大讯飞（subtitleThenLocalTts）读出并同步上镜。
     * agent 没产出文字时用兜底台词，同样走讯飞阅读。
     *
     * @param userText     传给秘书的情境/触发文本（作为 user 消息；秘书人设在 system 参数）
     * @param showOnGlasses 保留入参（当前字幕统一由 subtitleThenLocalTts 在发声时同步，不在生成阶段预推）
     * @param system       秘书人设（system 提示词）；默认“回应对方”，react 通道传 REACT_SYSTEM
     * @param fallback     agent 空产出时的兜底台词（各场景各传：晨报/选件等）
     */
    fun speakByAgent(userText: String, showOnGlasses: Boolean, system: String = StepAgent.SYSTEM, fallback: String = StepAgent.REACT_FALLBACK) {
        agentJob?.cancel()
        if (showOnGlasses) typewriterJob?.cancel()  // 讯飞字幕占用 content，先停结算打字机避免抢屏
        agentJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { streamAgent(userText, showOnGlasses, system) }.getOrElse {
                    onLog("⚠️ Agent 文字生成异常：${it.message}"); AgentResult(false, "")
                }
            }
            // agent 只生成文字；拿到文字（或兜底台词）统一交给科大讯飞阅读 + 声画同步字幕
            val line = r.text.takeIf { it.isNotBlank() } ?: fallback
            if (r.text.isBlank()) onLog("⚠️ Agent 未产出文字，改用兜底台词交讯飞阅读：$line")
            else onLog("Agent 生成文字，交讯飞阅读：${line.take(30)}")
            subtitleThenLocalTts(line)
        }
    }
    
    /** agent 流式结果：gotAudio 已废弃（现不再走阶跃音频，恒为 false）；text=流式累加的回复文字。 */
    private data class AgentResult(val gotAudio: Boolean, val text: String)

    /** 去掉推理模型输出的 <think>…</think> 段（含跨行）及残留的孤立标签，返回清洗后的正文。 */
    private fun stripThink(raw: String): String =
        raw.replace(Regex("(?is)<think>.*?</think>"), "")
           .replace(Regex("(?i)</?think>"), "")
           .trim()

    /**
     * 台词净化：只留秘书真正要念出口的话——剔除括号/星号里的动作神态描写（如（叹气）*翻文件*），
     * 并去掉所有双引号。提示词已禁止这些写法，此处是模型不听话时的兜底。
     */
    private fun sanitizeSpeech(raw: String): String =
        raw.replace(Regex("（[^（）]{0,20}）"), "")
           .replace(Regex("\\([^()]{0,20}\\)"), "")
           .replace(Regex("\\*[^*]{0,20}\\*"), "")
           .replace(Regex("【[^【】]{0,20}】"), "")
           .replace(Regex("[\"“”]"), "")
           .trim()

    /**
     * 真正跑流式请求（在 IO 线程调用，可被 agentJob.cancel() 打断）——只生成文字（不要音频）。
     * modalities=[text]、stream=true；逐行读 SSE：data:{...} → delta.content 累加文字。
     * @param history 多轮记忆（场景③实时聊天用）：(role, content) 列表，插在 system 与本轮 user 之间
     * @return AgentResult(false, text)：上层拿 text 交给科大讯飞阅读
     */
    private suspend fun streamAgent(userText: String, showOnGlasses: Boolean, system: String = StepAgent.SYSTEM, history: List<Pair<String, String>> = emptyList()): AgentResult {
        val body = JSONObject().apply {
            put("model", StepAgent.MODEL)
            put("modalities", JSONArray().put("text"))   // 只要文字，不再要阶跃端到端音频
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                for ((role, content) in history) put(JSONObject().apply { put("role", role); put("content", content) })
                put(JSONObject().apply { put("role", "user"); put("content", userText) })
            })
        }.toString()
        val conn = (URL(StepAgent.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${StepTts.API_KEY}")
            setRequestProperty("Accept", "text/event-stream")
        }
        val transcript = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                onLog("⚠️ Agent HTTP $code：${err?.take(200)}")
                return AgentResult(false, "")
            }
            onLog("Agent 文字生成开始（模型=${StepAgent.MODEL}）：$userText")
            var dataLines = 0
            var loggedFirst = false
            conn.inputStream.bufferedReader().use { reader ->
                while (coroutineContext.isActive) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    dataLines++
                    if (!loggedFirst) { onLog("ℹ️ Agent 首块：${payload.take(400)}"); loggedFirst = true }
                    val delta = runCatching {
                        JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    }.getOrNull() ?: continue
                    // 纯文字模式在 delta.content；垫一下：若模型仍回音频风格则取 delta.audio.transcript
                    val piece = delta.optString("content", "").ifEmpty {
                        delta.optJSONObject("audio")?.optString("transcript", "").orEmpty()
                    }
                    if (piece.isNotEmpty()) transcript.append(piece)
                }
            }
            if (dataLines == 0) onLog("⚠️ Agent 未收到任何 data 行（流可能被服务端立即关闭）")
            val clean = sanitizeSpeech(stripThink(transcript.toString()))
            onLog("Agent 完成：文字=${clean.length}字：${clean.take(40)}")
            return AgentResult(false, clean)
        } finally {
            conn.disconnect()
        }
    }

    /** 新建一个流式 PCM 播放的 AudioTrack（16bit 单声道，采样率见 StepAgent.SAMPLE_RATE）。 */
    private fun newPcmTrack(): AudioTrack {
        val sr = StepAgent.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sr)  // 约 0.5s 缓冲，兼顾延迟与卡顿
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** 把 agent 流式文字（标题固定「秘书」）推到眼镜 content。IO 线程调用，切回主线程发。 */
    private fun pushAgentText(text: String) {
        if (!started) return
        beginTalking(3000)   // 流式每出一段字就刷新看门狗：说话期间保持 talk 帧，末 token 后约 3s 回 idle
        val json = buildUpdateJson("秘书", text, npc = false)
        mainHandler.post { pushViewQuiet(json) }
    }

    /** 调阶跃 /v1/audio/speech，返回 mp3 字节；失败抛异常（交给上层回退）。在 IO 线程调用。 */
    private fun synthByStepFun(text: String): ByteArray {
        val body = JSONObject().apply {
            put("model", StepTts.MODEL)
            put("input", text)
            put("voice", StepTts.VOICE)
            put("response_format", "mp3")
            put("instruction", StepTts.INSTRUCTION)
        }.toString()
        val conn = (URL(StepTts.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${StepTts.API_KEY}")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                onLog("⚠️ 阶跃TTS HTTP $code：${err?.take(200)}")
                return ByteArray(0)
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** 把 mp3 字节写入缓存文件并用 MediaPlayer 从手机扬声器播放。在主线程调用。 */
    private fun playMp3(mp3: ByteArray, text: String) {
        try {
            val f = File(activity.cacheDir, "step_tts.mp3")
            f.writeBytes(mp3)
            mediaPlayer?.let { runCatching { it.release() } }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnPreparedListener { it.start(); onLog("阶跃TTS播报：$text") }
                setOnErrorListener { _, what, extra ->
                    onLog("⚠️ MediaPlayer 出错 what=$what extra=$extra，回退讯飞：$text")
                    speakLocal(text); true
                }
                prepareAsync()
            }
        } catch (e: Throwable) {
            onLog("⚠️ 播放阶跃音频失败：${e.message}，回退讯飞：$text")
            speakLocal(text)
        }
    }

    /** 手机本地 TTS（讯飞）兜底播报。 */
    private fun speakLocal(text: String) {
        val tts = localTts
        if (tts == null) { onLog("TTS 未就绪，无法播报：$text"); return }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val ret = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "reality-" + System.currentTimeMillis())
        if (ret == TextToSpeech.SUCCESS) onLog("TTS(手机兜底)：$text")
        else onLog("⚠️ TTS.speak 返回错误 ret=$ret（语音未能播报）：$text")
    }

    /**
     * 声画同步播报：先用阶跃单向 TTS 合成整段 mp3，在播放开始的瞬间启动字幕打字机，
     * 并按音频总时长匀速揭示每个字，做到“声音一响、字就跟着一个个冒出”。
     * 阶跃不可用时回退讯飞，在 onStart 时按经验语速启动字幕。
     */
    private fun speakWithSubtitle(text: String) {
        if (StepTts.API_KEY.isBlank()) { subtitleThenLocalTts(text); return }
        scope.launch {
            val mp3 = withContext(Dispatchers.IO) { runCatching { synthByStepFun(text) }.getOrNull() }
            if (mp3 == null || mp3.isEmpty()) {
                onLog("⚠️ 阶跃TTS失败，回退讯飞(带字幕)：$text")
                subtitleThenLocalTts(text)
            } else {
                playMp3WithSubtitle(mp3, text)
            }
        }
    }

    /** 播阶跃 mp3，在 onPrepared(声音真正开始) 那一刻启动字幕打字机，按 duration 匀速揭示，实现声画同步。 */
    private fun playMp3WithSubtitle(mp3: ByteArray, text: String) {
        try {
            val f = File(activity.cacheDir, "step_tts.mp3")
            f.writeBytes(mp3)
            mediaPlayer?.let { runCatching { it.release() } }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnPreparedListener { mp ->
                    val durMs = mp.duration.takeIf { it > 0 } ?: (text.length * 190)
                    mp.start()
                    onLog("阶跃TTS播报(声画同步 ${durMs}ms)：$text")
                    startSubtitleTypewriter(text, durMs)
                }
                setOnErrorListener { _, what, extra ->
                    onLog("⚠️ MediaPlayer 出错 what=$what extra=$extra，回退讯飞(带字幕)：$text")
                    subtitleThenLocalTts(text); true
                }
                prepareAsync()
            }
        } catch (e: Throwable) {
            onLog("⚠️ 播放阶跃音频失败：${e.message}，回退讯飞(带字幕)：$text")
            subtitleThenLocalTts(text)
        }
    }

    /** 字幕打字机：将 text 在 durMs 内逐字揭示到眼镜 content（标题固定「秘书」），与语音时长对齐。 */
    private fun startSubtitleTypewriter(text: String, durMs: Int) {
        typewriterJob?.cancel()
        cancelAutoClear()          // 新一段台词开始：取消上一次待清屏
        glassesCleared = false     // 秘书重新出现
        val n = text.length.coerceAtLeast(1)
        val interval = (durMs.toLong() / n).coerceIn(40L, 400L)  // 每字间隔，限幅避免过快/过慢
        beginTalking(durMs)   // 字幕与语音同时起：整段期间秘书保持 talk 帧
        typewriterJob = scope.launch {
            for (i in 1..n) {
                if (started) pushViewQuiet(buildUpdateJson("秘书", text.substring(0, i), npc = false))
                delay(interval)
            }
            endTalking()   // 字幕放完：秘书回 idle
            scheduleAutoClear()   // 秘书说完，隔一段时间自动清屏，让玩家看清电脑操作
        }
    }

    /** 阶跃不可用时的声画同步兜底：用讯飞播，在 onStart(真正发声) 时按经验语速启动字幕。 */
    private fun subtitleThenLocalTts(text: String) {
        cancelAutoClear()          // 秘书要开口：取消上一次待清屏
        glassesCleared = false     // 恢复帧循环（秘书重新出现）
        val tts = localTts
        if (tts == null) {
            onLog("TTS 未就绪：$text")
            if (started) pushViewQuiet(buildUpdateJson("秘书", text, npc = false))
            return
        }
        // 讯飞不给音频总时长：用经验值 ~190ms/字 估算字幕节奏，onStart 时启动
        val approxMs = text.length * 190
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { mainHandler.post { startSubtitleTypewriter(text, approxMs) } }
            override fun onDone(id: String?) {}
            @Deprecated("deprecated")
            override fun onError(id: String?) { onLog("⚠️ 讯飞播报出错 id=$id") }
            override fun onError(id: String?, code: Int) { onLog("⚠️ 讯飞播报出错 id=$id code=$code") }
        })
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        val ret = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "reality-" + System.currentTimeMillis())
        if (ret == TextToSpeech.SUCCESS) onLog("讯飞播报(声画同步)：$text")
        else {
            onLog("⚠️ 讯飞 speak 失败 ret=$ret：$text")
            if (started) pushViewQuiet(buildUpdateJson("秘书", text, npc = false))
        }
    }

    /**
     * 初始视图（openCustomView 的 viewData）：布局树，含 id="title" / id="content" 两个 TextView，
     * 供后续 buildUpdateJson 增量刷新。单色绿显：文字 #FF00FF00，背景透明。
     */
    private fun buildViewJson(title: String, body: String, severity: String): String {
        val withSecretary = hasSecretaryAssets()
        val titleView = JSONObject().apply {
            put("type", "TextView")
            put("props", JSONObject().apply {
                put("id", "title")
                put("layout_width", "wrap_content")
                put("layout_height", "wrap_content")
                put("layout_centerHorizontal", "true")
                put("layout_marginTop", "40dp")
                put("text", title)
                put("textSize", "22sp")
                put("textColor", "#FF00FF00")
            })
        }
        val bodyView = JSONObject().apply {
            put("type", "TextView")
            put("props", JSONObject().apply {
                put("id", "content")
                put("layout_width", "wrap_content")
                put("layout_height", "wrap_content")
                put("layout_centerInParent", "true")   // 居中正文：等待画面 / 结算 / 无头像信息用（不显头像时才可能居中）
                put("text", body)
                put("textSize", "16sp")
                put("textColor", "#FF00FF00")
            })
        }
        // 右侧正文框：固件在 update 时只改 text/textSize/gravity，不会重跑父布局（layout_* 在 open 时就冻结），
        // 所以“人物+对话”的正文必须用一个 open 时就定位在头像右侧的独立 TextView，才可能真正不与左侧头像重叠。
        // 关键：用 layout_toRightOf 直接锚定到头像元素(id=secretary)的右边 —— 这是 RelativeLayout 的标准做法，
        // 不依赖 marginStart 数值（match_parent + marginStart 会被固件当成“从 x=0 铺满、忽略边距”导致重叠）。
        // 平时 text="" 不占可见空间；有头像(withSecretary)时才加入，routing 见 buildUpdateJson。
        val bodyViewRight = JSONObject().apply {
            put("type", "TextView")
            put("props", JSONObject().apply {
                put("id", "contentR")
                put("layout_width", "match_parent")
                put("layout_height", "wrap_content")
                put("layout_toRightOf", "secretary")   // 锚在头像右边：左缘=头像右缘，绝不重叠
                put("layout_toEndOf", "secretary")     // 双写：兼容只认 toEndOf 的固件
                put("layout_centerVertical", "true")
                put("layout_marginStart", "12dp")       // 与头像的间隙（作锚点偏移；被忽略也只是贴着头像右缘）
                put("layout_marginLeft", "12dp")
                put("layout_marginEnd", "10dp")
                put("layout_marginRight", "10dp")
                put("text", "")
                put("textSize", "16sp")
                put("textColor", "#FF00FF00")
                put("gravity", "left")
            })
        }
        val secretaryView = JSONObject().apply {
            put("type", "ImageView")
            put("props", JSONObject().apply {
                put("id", "secretary")
                put("layout_width", "96dp")
                put("layout_height", "96dp")
                put("layout_alignParentLeft", "true")    // 贴最左边
                put("layout_centerVertical", "true")
                put("layout_marginStart", "0dp")         // 零边距：贴到最最最左
                // 初始不设 name：图标上传完成后由帧循环设置，避免"图标未就绪"时显示异常
            })
        }
        return JSONObject().apply {
            put("type", "RelativeLayout")
            put("props", JSONObject().apply {
                put("layout_width", "match_parent")
                put("layout_height", "match_parent")
                put("backgroundColor", "#00000000")
            })
            // 仅当 assets 里有秘书图时才加入 ImageView：无图时初始视图与旧版完全一致，零回归风险。
            // secretary 先于 content 加入：万一长文本与秘书重叠，文字绘制在上层保证可读。
            put("children", JSONArray().apply {
                put(titleView)
                if (withSecretary) put(secretaryView)
                put(bodyView)
                if (withSecretary) put(bodyViewRight)   // 右侧正文框：人物+对话时用它，绝不压到左侧头像
            })
        }.toString()
    }

    /** 增量更新（updateCustomView）：刷新 title；正文写到右侧框(contentR)、清空居中框(content)。 */
    private fun buildUpdateJson(title: String, body: String, npc: Boolean = lastNpc): String {
        lastTitle = title
        lastBody = body
        lastNpc = npc
        // 人物+对话统一走右侧正文框（open 时已定位在头像右侧、绝不重叠）；无秘书图时退回居中框。
        val hasHead = hasSecretaryAssets()
        val targetId = if (hasHead) "contentR" else "content"
        return JSONArray().apply {
            put(JSONObject().apply {
                put("action", "update")
                put("id", "title")
                put("props", JSONObject().put("text", title))
            })
            put(JSONObject().apply {
                put("action", "update")
                put("id", targetId)
                // 只改视图内属性（text/textSize/gravity）——这些 update 生效；位置靠 open 时的布局，已冻结。
                put("props", JSONObject().apply {
                    put("text", body)
                    put("textSize", "16sp")
                    put("gravity", "left")
                })
            })
            // 有头像：清空居中框，避免上一屏(等待/结算)的居中文字与右侧正文并存。
            if (hasHead) put(JSONObject().apply {
                put("action", "update")
                put("id", "content")
                put("props", JSONObject().put("text", ""))
            })
            secretaryEntryOrNull()?.let { put(it) }   // 每次更新都带上秘书，确保它不会被文字更新顶掉
        }.toString()
    }

    /**
     * 秘书的更新条目：name 指向已上传图标 + 完整布局。每次都带完整布局，
     * 抵“整体重排”语义（部分固件对 update 只认本次载荷里的元素）把位置/元素丢掉。
     * 图标未就绪（iconsReady=false）时返回 null，与旧版行为一致。
     */
    private fun secretaryEntryOrNull(): JSONObject? {
        // NPC 画面优先用 NPC 专属头像；否则用秘书当前帧。两者都无则不出头像。
        val name = npcHeadName ?: secretaryName ?: return null
        if (!iconsReady) return null
        return JSONObject().apply {
            put("action", "update")
            put("id", "secretary")
            put("props", JSONObject().apply {
                put("name", name)
                put("visibility", "visible")   // 显式置可见：覆盖结算时的 gone/0dp，让秘书重新出现
                put("layout_width", "96dp")
                put("layout_height", "96dp")
                put("layout_alignParentLeft", "true")
                put("layout_centerVertical", "true")
                put("layout_marginStart", "0dp")
            })
        }
    }

    /**
     * 隐藏秘书的更新条目（结算等不该出秘书的画面用）。
     * 关键靠 name="" 清空图标（固件对未支持的 visibility/0dp 会直接忽略，但 name 是它明确支持的属性，
     * 指向空/无图时 ImageView 就没东西可画）；visibility=gone + 0dp 作双保险。
     */
    private fun secretaryHiddenEntry(): JSONObject = JSONObject().apply {
        put("action", "update")
        put("id", "secretary")
        put("props", JSONObject().apply {
            put("name", Secretary.BLANK)   // 指向全透明空图标：主手段，彻底不可见且无缺图占位
            put("visibility", "gone")
            put("layout_width", "0dp")
            put("layout_height", "0dp")
        })
    }

    // ============================ 秘书角色（图标上传 + 帧动画） ============================

    /**
     * 会话就绪后初始化秘书：从 assets 加载帧 → 转绿通道+压缩 → setIcons 上传 → 重推一次当前画面（带秘书）。
     * 无图（assets/secretary/idle 为空）则静默跳过。已就绪则直接重推一次。
     */
    private fun initSecretary() {
        if (iconsReady) { pushViewQuiet(buildUpdateJson(lastTitle, lastBody)); startSecretaryLoop(); return }
        scope.launch {
            val sec = withContext(Dispatchers.IO) { runCatching { loadSecretaryIcons() }.getOrNull() }
            val npc = withContext(Dispatchers.IO) { runCatching { loadNpcIcons() }.getOrNull() }
            val secFrames = sec?.frames ?: emptyList()
            val npcFrames = npc?.frames ?: emptyList()
            // 合并秘书帧 + NPC 头像，一次性上传（同一批 setIcons）；确保有一张空图用于隐藏头像。
            val allFrames = ArrayList<Pair<String, String>>()
            allFrames.addAll(secFrames)
            allFrames.addAll(npcFrames)
            if (allFrames.isEmpty()) {
                onLog("ℹ️ 未发现秘书/NPC 图片（assets/secretary、assets/npc 为空），跳过头像显示")
                return@launch
            }
            if (allFrames.none { it.first == Secretary.BLANK }) {
                allFrames.add(Secretary.BLANK to bitmapToBase64(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
            }
            idleFrames = sec?.idle ?: emptyList()
            talkFrames = sec?.talk ?: emptyList()
            npcAssets = npc?.assets ?: emptyList()
            val ok = sendIcons(buildIconsJson(allFrames))
            onLog("头像图标上传 ${allFrames.size} 张(秘书 idle=${idleFrames.size}/talk=${talkFrames.size}, NPC=${npcAssets.size}) → success=$ok")
            if (!ok) return@launch
            secretaryName = idleFrames.firstOrNull() ?: talkFrames.firstOrNull()
            iconsReady = true
            // 上传后先重推一次当前画面（带头像），让它马上出现且保留现有文字；随后启动帧动画。
            delay(300)   // 给眼镜一点时间落地图标再引用
            pushViewQuiet(buildUpdateJson(lastTitle, lastBody))
            startSecretaryLoop()   // 帧动画：不说话循环 idle 帧，说话时循环 talk 帧
        }
    }

    /**
     * 秘书帧动画循环：
     *  - 说话(secretaryTalking=true 且有 talk 帧)：按 TALK_FRAME_MS 循环 talk 帧（口型动画）；
     *  - 待机：长时间停在“睁眼”帧(idle[0])，每隔 IDLE_HOLD_MS 快速过一遍其余帧完成一次眨眼。
     * ⭐仅在帧真正变化时才整体重推(走 buildUpdateJson)：既避免固件在“只发 secretary 一项”时把
     * 文字/布局顶掉，又大幅减少与字幕打字机抢链路。idle↔talk 切换时从第 0 帧重新起。
     */
    private fun startSecretaryLoop() {
        if (!iconsReady) return
        secretaryFrameJob?.cancel()
        secretaryFrameJob = scope.launch {
            var talkIdx = 0
            var idleIdx = 0
            var wasTalking = false
            while (isActive && started) {
                if (settlementActive) { delay(Secretary.TALK_FRAME_MS); continue }   // 结算画面期间不抓 content
                if (glassesCleared) { delay(Secretary.TALK_FRAME_MS); continue }      // 画面已清空：不推秘书帧，保持镜片透明
                if (lastNpc) { delay(Secretary.TALK_FRAME_MS); continue }            // NPC 画面期间头像固定为 NPC 图，不跑秘书帧动画
                val talking = secretaryTalking && talkFrames.isNotEmpty()
                if (talking != wasTalking) { talkIdx = 0; idleIdx = 0; wasTalking = talking }   // 切换时帧号归零
                if (talking) {
                    val name = talkFrames[talkIdx % talkFrames.size]
                    talkIdx++
                    if (name != secretaryName) { secretaryName = name; pushViewQuiet(buildUpdateJson(lastTitle, lastBody)) }
                    delay(Secretary.TALK_FRAME_MS)
                } else {
                    val frames = idleFrames
                    if (frames.isEmpty()) { delay(Secretary.IDLE_HOLD_MS); continue }
                    val name = frames[idleIdx % frames.size]
                    if (name != secretaryName) { secretaryName = name; pushViewQuiet(buildUpdateJson(lastTitle, lastBody)) }
                    val atOpenEye = (idleIdx % frames.size) == 0   // 回到睁眼帧：长保持；其余帧：快速过
                    idleIdx++
                    delay(if (atOpenEye) Secretary.IDLE_HOLD_MS else Secretary.BLINK_FRAME_MS)
                }
            }
        }
    }

    /** 标记秘书进入说话状态；estMs 后（加缓冲）看门狗自动回 idle，避免回调缺失时卡在 talk。可任意线程调。 */
    private fun beginTalking(estMs: Int) {
        secretaryTalking = true
        talkingWatchdog?.let { mainHandler.removeCallbacks(it) }
        val w = Runnable { secretaryTalking = false }
        talkingWatchdog = w
        mainHandler.postDelayed(w, (estMs.toLong() + 600).coerceIn(1000L, 30000L))
    }

    /** 结束说话状态（秘书回 idle）。 */
    private fun endTalking() {
        talkingWatchdog?.let { mainHandler.removeCallbacks(it) }
        talkingWatchdog = null
        secretaryTalking = false
    }

    /**
     * 秘书/NPC 说完话后自动清空眼镜画面：隐藏头像、清空标题与正文，让镜片透明，
     * 玩家可看清电脑上的操作。晨报/结算不走这里（常驻，等玩家确认）。
     */
    private fun clearGlasses() {
        glassesCleared = true       // 先置位：暂停帧循环重推，避免秘书头像又被 idle 帧刷回来
        endTalking()
        npcHeadName = null
        lastTitle = ""
        lastBody = ""
        lastNpc = false
        if (!started) return
        val json = JSONArray().apply {
            put(JSONObject().apply { put("action", "update"); put("id", "title"); put("props", JSONObject().put("text", "")) })
            put(JSONObject().apply { put("action", "update"); put("id", "content"); put("props", JSONObject().put("text", "")) })
            if (hasSecretaryAssets()) put(JSONObject().apply { put("action", "update"); put("id", "contentR"); put("props", JSONObject().put("text", "")) })
            if (iconsReady) put(secretaryHiddenEntry())   // 隐藏头像（指向全透明空图）
        }.toString()
        pushViewQuiet(json)
        onLog("画面已清空（秘书/NPC 说完，等待下一事件）")
    }

    /** 安排在 delayMs 后自动清空眼镜（秘书/NPC 说完话用）。会先取消上一个待清屏任务。 */
    private fun scheduleAutoClear(delayMs: Long = Secretary.CLEAR_AFTER_MS) {
        cancelAutoClear()
        val t = Runnable { clearGlasses() }
        autoClearTask = t
        mainHandler.postDelayed(t, delayMs)
    }

    /** 取消待执行的自动清屏（有新画面接管时调用，避免把新画面清掉）。 */
    private fun cancelAutoClear() {
        autoClearTask?.let { mainHandler.removeCallbacks(it) }
        autoClearTask = null
    }

    /** 秘书图标集：idle/talk 的图标名列表 + 全部帧(名,base64) + setIcons 所需 JSON。 */
    private data class SecretaryIcons(
        val idle: List<String>,
        val talk: List<String>,
        val frames: List<Pair<String, String>>,
        val iconsJson: String
    )

    /**
     * 从 assets 读取秘书帧，逐张缩到 ≤128px、转绿通道、转 base64，构造 setIcons 的 JSON 数组。
     * 图标名约定：sec_idle_0/1/…、sec_talk_0/1/…，与 ImageView 的 name 对应。在 IO 线程调。
     */
    private fun loadSecretaryIcons(): SecretaryIcons {
        val am = activity.assets
        fun listImages(dir: String): List<String> =
            (runCatching { am.list(dir)?.toList() }.getOrNull() ?: emptyList())
                .filter {
                    val n = it.lowercase()
                    n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                }
                .sorted()
        val frames = ArrayList<Pair<String, String>>()
        val idleNames = ArrayList<String>()
        val talkNames = ArrayList<String>()
        fun encode(dir: String, file: String): String? = runCatching {
            am.open("$dir/$file").use { ins ->
                val bmp = BitmapFactory.decodeStream(ins) ?: return null
                bitmapToBase64(toGreenChannel(scaleWithin(bmp, Secretary.MAX_PX)))
            }
        }.getOrNull()
        listImages(Secretary.IDLE_DIR).forEachIndexed { i, f ->
            encode(Secretary.IDLE_DIR, f)?.let { val n = "sec_idle_$i"; idleNames.add(n); frames.add(n to it) }
        }
        listImages(Secretary.TALK_DIR).forEachIndexed { i, f ->
            encode(Secretary.TALK_DIR, f)?.let { val n = "sec_talk_$i"; talkNames.add(n); frames.add(n to it) }
        }
        // 无美术：返回空 frames，交给上层跳过秘书（不上传 blank）
        if (idleNames.isEmpty() && talkNames.isEmpty()) return SecretaryIcons(idleNames, talkNames, frames, "[]")
        // 追加一张全透明空图标：“隐藏秘书”时把 name 指向它（比 name="" 更稳，绝不会出现缺图占位）
        frames.add(Secretary.BLANK to bitmapToBase64(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
        // setIcons 依据眼镜端以 name 解析图标；top-level 为数组。
        // ⚙️ base64 同时用 data / base64 两个键写入（兼容不同固件字段名，多余键会被忽略）；
            //   若图标仍不显示，先看日志 success 是否 true，再试把数组包成 {"icons":[...]}。
        val arr = JSONArray()
        for ((n, b64) in frames) arr.put(JSONObject().apply {
            put("name", n)
            put("data", b64)
            put("base64", b64)
        })
        return SecretaryIcons(idleNames, talkNames, frames, arr.toString())
    }

    /** 把 (图标名, base64) 列表构造成 setIcons 所需的 JSON 数组（data/base64 双键兼容不同固件）。 */
    private fun buildIconsJson(frames: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((n, b64) in frames) arr.put(JSONObject().apply {
            put("name", n)
            put("data", b64)
            put("base64", b64)
        })
        return arr.toString()
    }

    /** NPC 头像：图标名 + 从文件名解析出的性别/年龄。 */
    private data class NpcAsset(val iconName: String, val gender: String, val age: String)

    /** NPC 加载结果：角色列表 + 待上传帧(图标名, base64)。 */
    private data class NpcLoad(val assets: List<NpcAsset>, val frames: List<Pair<String, String>>)

    /**
     * 从 assets/npc 读取 NPC 头像（文件名约定 NPC_<gender>_<age><序号>.png，如 NPC_male_average1.png），
     * 逐张缩到 ≤128px、转绿通道、转 base64；图标名=文件名（小写、非法字符转 _）。在 IO 线程调。
     */
    private fun loadNpcIcons(): NpcLoad {
        val am = activity.assets
        val files = (runCatching { am.list("npc")?.toList() }.getOrNull() ?: emptyList())
            .filter {
                val n = it.lowercase()
                n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            }
            .sorted()
        val assets = ArrayList<NpcAsset>()
        val frames = ArrayList<Pair<String, String>>()
        fun encode(file: String): String? = runCatching {
            am.open("npc/$file").use { ins ->
                val bmp = BitmapFactory.decodeStream(ins) ?: return null
                bitmapToBase64(toGreenChannel(scaleWithin(bmp, Secretary.MAX_PX)))
            }
        }.getOrNull()
        for (f in files) {
            val b64 = encode(f) ?: continue
            val stem = f.substringBeforeLast('.')
            val iconName = stem.lowercase().replace(Regex("[^a-z0-9_]"), "_")   // 图标名安全化
            val (g, a) = parseGenderAge(stem)
            assets.add(NpcAsset(iconName, g, a))
            frames.add(iconName to b64)
        }
        return NpcLoad(assets, frames)
    }

    /**
     * 从文件名（如 NPC_male_average1）解析出 (gender, age)：按 "_" 分段，第 2 段=性别、第 3 段去尾部序号=年龄。
     * 解析不出时返回空串（上层会回退到默认音效/第一张图）。
     */
    private fun parseGenderAge(stem: String): Pair<String, String> {
        val parts = stem.lowercase().split("_")
        val gender = parts.getOrNull(1)?.takeIf { it == "male" || it == "female" } ?: ""
        val age = (parts.getOrNull(2) ?: "").trimEnd { it.isDigit() }
        return gender to age
    }

    /** 等比缩放使长边 ≤ max（已在限内则原图返回）。 */
    private fun scaleWithin(src: Bitmap, max: Int): Bitmap {
        if (src.width <= max && src.height <= max) return src
        val ratio = minOf(max.toFloat() / src.width, max.toFloat() / src.height)
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 把图转成“线条→绿通道”（R=B=0, G=强度, 保留 alpha），保证单色绿光波导上可见。
     * 镜片只有“亮”处发绿，而素材存在两种风格：黑线透明底（旧 NPC 图，需反相成高绿）、
     * 白线透明底（rokid-action-set 新秘书，亮度直通）。逐图自动判断：取不透明像素的平均亮度，
     * 偏暗（<128）则反相、偏亮则直通；无不透明像素时回退 Secretary.INVERT_LUMA。
     * 绿强度以 alpha 门控：透明底(alpha=0)不发光，无泛绿泄漏。
     */
    private fun toGreenChannel(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // 第一遍：统计不透明像素的平均亮度，自动判断该图是“暗线”还是“亮线”
        var lumSum = 0L
        var lumCnt = 0
        for (c in px) {
            val a = (c ushr 24) and 0xFF
            if (a < 32) continue
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            lumSum += (r * 299 + g * 587 + b * 114) / 1000
            lumCnt++
        }
        val invert = if (lumCnt > 0) (lumSum / lumCnt) < 128 else Secretary.INVERT_LUMA
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            val base = if (invert) 255 - lum else lum
            val green = (base * a / 255).coerceIn(0, 255)   // 以不透明度门控，透明处不发光
            px[i] = (a shl 24) or (green shl 8)   // R=0, G=green, B=0
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 位图→PNG→base64（NO_WRAP：不插换行，否则传给 SDK 会出错）。 */
    private fun bitmapToBase64(bmp: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 上传图标：高层 CxrSession 未暴露 setIcons，故反射取到其内部 ExternalAppClient，调 customViewSetIcons(json)。
     * （ExternalAppClient.customViewSetIcons 内部转发给 IMediaStreamService.setIcons，再经 AIDL 到眼镜端渲染。）
     */
    private fun sendIcons(json: String): Boolean {
        val client = linkClient() ?: run { onLog("⚠️ 未取到 ExternalAppClient，无法上传秘书图标"); return false }
        val m = runCatching { client.javaClass.getMethod("customViewSetIcons", String::class.java) }.getOrNull()
            ?: run { onLog("⚠️ 未找到 customViewSetIcons 方法"); return false }
        return runCatching { (m.invoke(client, json) as? Boolean) ?: false }
            .getOrElse { onLog("⚠️ setIcons 调用异常：${it.message}"); false }
    }

    /** 反射：从当前 session 对象（及父类）找出类型为 ExternalAppClient 的字段实例。按类型匹配，免受混淆字段名影响。 */
    private fun linkClient(): Any? {
        val s: Any = session ?: return null
        var cls: Class<*>? = s.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (f.type.name.contains("ExternalAppClient")) {
                    f.isAccessible = true
                    return runCatching { f.get(s) }.getOrNull()
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /** assets/secretary/idle|talk 下是否至少有一张图（判断是否启用秘书）。 */
    private fun hasSecretaryAssets(): Boolean = runCatching {
        (activity.assets.list(Secretary.IDLE_DIR)?.isNotEmpty() == true) ||
            (activity.assets.list(Secretary.TALK_DIR)?.isNotEmpty() == true)
    }.getOrDefault(false)

    fun disconnect() {
        agentJob?.cancel(); agentJob = null
        typewriterJob?.cancel(); typewriterJob = null
        secretaryFrameJob?.cancel(); secretaryFrameJob = null
        cancelAutoClear()
        endTalking()
        iconsReady = false   // 下次重连是新服务，需重新上传图标
        stopTypewriterSfx()
        stateJob?.cancel(); stateJob = null
        runCatching { session?.close() }
        session = null
        started = false
        onLog("已断开/重置会话")
    }

    fun release() {
        disconnect()
        runCatching { scope.cancel() }
        stopTypewriterSfx()
        mediaPlayer?.let { runCatching { it.release() } }
        mediaPlayer = null
        localTts?.shutdown()
        localTts = null
    }
}
