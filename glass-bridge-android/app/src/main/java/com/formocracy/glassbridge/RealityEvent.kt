package com.formocracy.glassbridge

/**
 * 与 FORMOCRACY(Godot) 游戏约定的「现实事件」协议。
 * 游戏在关键节点（晨报 / 秘书台词 / 验收生效 / 后果回流 / 日终）通过 WebSocket 发来一条 JSON。
 *
 * 示例：
 * { "type":"reality_receipt", "day":5, "formId":"hospital_urgent_07",
 *   "outcome":"void", "severity":"critical",
 *   "title":"第七码头 · 医院急件", "body":"文件未验收 · 现实未更新 · 触发死亡回执" }
 */
data class RealityEvent(
    val type: String,           // morning_briefing | secretary_line | secretary_react | npc_line | reality_receipt | consequence | day_report | secretary_daybrief | secretary_briefing_chat | secretary_pick_comment | secretary_chat_start | secretary_chat_stop
    val day: Int? = null,
    val formId: String? = null,
    val outcome: String? = null,     // approved | void | delayed ...
    val severity: String? = "normal", // normal | warning | critical
    val title: String? = null,
    val body: String? = null,
    val text: String? = null,        // secretary_line 用：要念出的台词；secretary_react 用：可选的附加备注
    val lines: List<String>? = null, // day_report 用：每日结算的逐行字段（只显示不朗读）
    // ↓ secretary_react 用：秘书 agent 据此“看局势、自己生成台词”的结构化上下文
    val phase: String? = null,       // intake(新包裹到台) | verdict(盖章受理后)
    val parcelNo: Int? = null,       // 包裹序号
    val weight: Int? = null,         // 包裹重量(克)
    val dest: String? = null,        // 目的地
    val due: Double? = null,         // 应收邮资
    val applied: Double? = null,     // 玩家已贴金额
    // ↓ npc_line 用：NPC 出场说话。text=台词、title=NPC 名字；gender/age 决定用哪段 people 音效
    val gender: String? = null,      // male | female
    val age: String? = null,         // young | average | old
    // portrait：点名指定某张预置立绘（优先于 gender/age 自动匹配），值=图片文件名（不带扩展名，大小写/分隔符不敏感），如 "NPC_female_young1"
    val portrait: String? = null,
    // ↓ secretary_daybrief 用：把当天晨报 + 玩家过往决策喂给秘书 agent，让它总结成“谈资”并暗记因果（不出声，纯后台准备）
    val newspaper: List<NewsItem>? = null,   // 今日晨报条目（城市近况）
    val decisions: List<DecisionRecord>? = null, // 玩家过往批/拒/暂存记录（供 agent 自行推断因果）
    // ↓ secretary_pick_comment 用：玩家下班选表单进验收机时，秘书据心情逐条心理暗示/讽刺
    val action: String? = null,      // add(加入验收队列) | remove(撤出)
    val remainingSlots: Int? = null, // 验收机剩余容量
    val factHint: String? = null     // 该表单已知后果的客观提示（可选，供 agent 阴阳时参考）
) {
    /** 晨报单条：headline=标题，body=正文摘要（可选） */
    data class NewsItem(
        val headline: String,
        val body: String? = null
    )

    /** 玩家决策记录：decision=approved|rejected|held */
    data class DecisionRecord(
        val formId: String? = null,
        val title: String? = null,
        val decision: String? = null,
        val day: Int? = null
    )

    /** 眼镜卡片标题 */
    fun cardTitle(): String = title ?: when (type) {
        "morning_briefing" -> "晨间指令 · Day ${day ?: "?"}"
        "secretary_line"   -> "秘书"
        "secretary_react"  -> "秘书"
        "secretary_daybrief"     -> "秘书"
        "secretary_briefing_chat" -> "秘书"
        "secretary_pick_comment"  -> "秘书"
        "npc_line"         -> "居民"
        "reality_receipt"  -> "现实回执"
        "consequence"      -> "后果回流"
        "day_report"       -> "每日结算 · Day ${day ?: "?"}"
        else               -> "验收机"
    }

    /** 眼镜卡片正文 */
    fun cardBody(): String = body ?: text ?: ""

    /**
     * 要 TTS 念出的内容（没有则不念）。
     * 只念“秘书台词”：仅当事件显式携带 text 时才播报；卡片正文 body 只显示不朗读。
     * （secretary_line 一定带 text；晨报/回执/后果/日终报告等只有 body，故不会被念。）
     */
    fun speech(): String? = text
}
