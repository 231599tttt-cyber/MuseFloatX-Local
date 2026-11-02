package com.example.musefloatx

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MuseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: MuseAccessibilityService? = null
        const val X_PACKAGE = "com.twitter.android"
        // 自己的包名写死：AccessibilityWindowInfo 本身没有 packageName，
        // 只能用窗口根节点的 packageName 判断是不是自己，写错就会编失败。
        const val OWN_PACKAGE = "com.example.musefloatx.local"
    }

    @Volatile private var lastCaptureMs = 0L
    // 在 X 还在前台时记下的文字位置快照（只记文字和坐标，不生成、不弹面板）。
    // 点确认那一刻前台已是自己的框选层，现读可能读不到 X，
    // 这份快照就是兜底：确认时按框去快照里取，仍只在点确认后才生成。
    @Volatile private var cachedItems: List<Triple<Int, Int, String>> = emptyList()
    @Volatile private var cachedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不是自动识别、不是自动生成：只在 X 内容变化时（X 还在前台、
        // 还没出框选层时）把屏上文字和坐标记一份，1 秒最多记一次。
        // 点球、框选、确认之前什么都不出；生成只在点确认后发生。
        try {
            val pkg = event?.packageName?.toString() ?: return
            if (pkg == OWN_PACKAGE) return
            val now = System.currentTimeMillis()
            if (now - lastCaptureMs < 1000) return
            lastCaptureMs = now
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() == OWN_PACKAGE) return
            val items = mutableListOf<Triple<Int, Int, String>>()
            collectAllItems(root, items, 0)
            if (items.isNotEmpty()) {
                cachedItems = items
                cachedMs = now
            }
        } catch (e: Exception) {
        }
    }

    private fun collectAllItems(node: AccessibilityNodeInfo?, out: MutableList<Triple<Int, Int, String>>, depth: Int) {
        if (node == null || depth > 30) return
        try {
            if (!node.isPassword) {
                val t = postTextOf(node)
                if (t != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    out.add(Triple(bounds.top, bounds.left, t))
                }
            }
            for (i in 0 until node.childCount) {
                collectAllItems(node.getChild(i), out, depth + 1)
            }
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun captureNow(force: Boolean = false) {
        // 无障碍事件在滑动时来得非常密，每次都全树遍历会让 X 和手机都卡。
        // 自动抓取节流到 1 秒一次，手动点球、点重新识别时 force=true 不节流。
        val now = System.currentTimeMillis()
        if (!force && now - lastCaptureMs < 1000) return
        lastCaptureMs = now
        try {
            val root = rootInActiveWindow ?: return
            // 通用识别：不再写死 X 包名，任何 App 的前台窗口都认。
            val lines = LinkedHashSet<String>()
            collect(root, lines, 0)
            val text = lines.joinToString("\n").take(4000)
            if (text.length >= 20) {
                ScreenSnapshotHub.update(text, root.packageName?.toString() ?: "")
            }
        } catch (e: Exception) {
            // 忽略单次读取失败，不中断服务
        }
    }

    /**
     * 通用框选无障碍兜底：只读用户框选区域内的文字，取中心点落在
     * 框内的叶子节点，按从上到下、从左到右排序去重。
     * 不写死任何 App 包名，X、ChatGPT、浏览器都走同一条路；
     * 框选主流程是截图裁剪 OCR，这里只在 OCR 失败时兜底。
     */
    fun captureRegion(rect: Rect): String {
        // 第 20/21 版读不到的真正原因（已用截图文案确认是旧版逻辑）：
        // 点确认那一刻，用户一直在跟自己的框选层打交道，
        // rootInActiveWindow 很可能给到自己的框选层而不是下面的 X，
        // 框里自然一个字都没有——跟框大小、过滤严不严都没关系，
        // 所以把框调大也没用。修法：不再只问“当前窗口”。
        // 第一路：翻所有窗口，跳过自己，找下面那层 App 的窗口来读，
        // 取读到最长的一扇（避免先读到状态栏、导航栏那种小窗口）。
        try {
            var best = ""
            val wins = try { windows } catch (e: Exception) { null }
            if (wins != null) {
                for (w in wins) {
                    try {
                        val root = w.root ?: continue
                        if (root.packageName?.toString() == OWN_PACKAGE) continue
                        val c = collectRegionText(root, rect, strictCenter = true)
                        if (c.length > best.length) best = c
                        if (c.length < 5) {
                            val inter = collectRegionText(root, rect, strictCenter = false)
                            if (inter.length > best.length) best = inter
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            if (best.length >= 5) return best
        } catch (e: Exception) {
        }
        // 第二路：老路，当前窗口直接读（中心先行，相交兜底）。
        try {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() != OWN_PACKAGE) {
                val center = collectRegionText(root, rect, strictCenter = true)
                if (center.length >= 5) return center
                val inter = collectRegionText(root, rect, strictCenter = false)
                if (inter.length >= 5) return inter
            }
        } catch (e: Exception) {
        }
        // 第三路：X 还在前台时记下的快照，60 秒内有效。
        // 按框过滤快照里的文字位置，仍只在点确认后用，不自动生成。
        try {
            val items = cachedItems
            if (items.isNotEmpty() && System.currentTimeMillis() - cachedMs < 60_000) {
                val seen = LinkedHashSet<String>()
                val sb = StringBuilder()
                val filtered = items.filter { (_, _, _) -> true }
                // 先按中心在框内取，取不到再按相交取。
                for (strict in listOf(true, false)) {
                    val picked = mutableListOf<Triple<Int, Int, String>>()
                    for ((top, left, t) in filtered) {
                        // 快照只存了 top/left，没存完整 bounds，这里用
                        // top/left 是否落在框内近似判断中心命中；
                        // 相交那遍则放宽为 top 在框上下 200px 内也算。
                        val inBox = if (strict) {
                            top in rect.top..rect.bottom
                        } else {
                            top in (rect.top - 200)..(rect.bottom + 200)
                        }
                        if (inBox) picked.add(Triple(top, left, t))
                    }
                    for ((_, _, t) in picked.sortedWith(compareBy({ it.first }, { it.second }))) {
                        if (seen.add(t)) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(t)
                        }
                    }
                    if (sb.length >= 5) return sb.toString().take(4000)
                    seen.clear()
                    sb.clear()
                }
            }
        } catch (e: Exception) {
        }
        return ""
    }

    private fun collectRegionText(root: AccessibilityNodeInfo, rect: Rect, strictCenter: Boolean): String {
        val items = mutableListOf<Triple<Int, Int, String>>()
        collectRegion2(root, rect, items, 0, strictCenter)
        val seen = LinkedHashSet<String>()
        val sb = StringBuilder()
        for ((_, _, t) in items.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (seen.add(t)) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(t)
            }
        }
        return sb.toString().take(4000)
    }

    /**
     * 帖子正文提取规则（结构过滤，不靠删关键词，也不动系统）：
     * - 只认 text，不认按钮、图标的 contentDescription。
     *   帖子正文在 TextView 的 text 里；“让 Grok 解释此帖子”“已验证”
     *   “发帖选项”这类是图标、按钮的 contentDescription，只在无障碍
     *   描述里出现，屏幕上并没有这行字，绝不能当成正文发给 AI。
     * - 按节点结构区分：Button / ImageButton / ImageView 等控件节点
     *   一律跳过，哪怕它上面有文字（那是按钮标签，不是正文）。
     * - 只收叶子节点：容器节点的 text 会和子节点重复，叶子 TextView
     *   才是真正的正文行。
     * - contentDescription 只有一种情况才收：节点本身就是 TextView、
     *   又是叶子、又没有 text——即自定义文本控件只能靠描述传文字时。
     *   ImageView 徽标、ImageButton 图标的描述永远不收。
     * 这样 X 以后换按钮文案也不会漏网，因为判断依据是“它是不是
     * 按钮、图标”，不是“它叫什么名字”。
     */
    private fun postTextOf(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        val className = node.className?.toString() ?: ""
        val isControl = className.endsWith("Button") ||
            className.endsWith("ImageView") ||
            className.endsWith("CheckBox") ||
            className.endsWith("RadioButton") ||
            className.endsWith("Switch") ||
            className.endsWith("SeekBar") ||
            className.endsWith("Spinner")
        if (isControl) return null
        if (node.childCount != 0) {
            // 有些 App 的正文节点本身带子节点，但子节点都没有文字，
            // 正文只在父节点 text 里。以前一律跳过父节点，这种帖子
            // 就一个字都读不到。现在放宽：子节点都没文字时收父节点，
            // 子节点有文字时仍只收子节点，不会重复。
            var childHasText = false
            for (i in 0 until node.childCount) {
                val c = try { node.getChild(i) } catch (e: Exception) { null }
                if (c != null && !c.text.isNullOrBlank()) { childHasText = true; break }
            }
            if (childHasText) return null
            val parentText = node.text?.toString()?.trim() ?: ""
            if (parentText.length >= 2 && parentText != "MuseFloatX") return parentText
            return null
        }
        val text = node.text?.toString()?.trim() ?: ""
        if (text.length >= 2 && text != "MuseFloatX") return text
        // 只有 TextView 叶子、且没有 text 时，才退而求其次收描述。
        if (className.endsWith("TextView")) {
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (desc.length >= 2) return desc
        }
        return null
    }

    private fun collectRegion2(node: AccessibilityNodeInfo?, rect: Rect, out: MutableList<Triple<Int, Int, String>>, depth: Int, strictCenter: Boolean) {
        if (node == null || depth > 30) return
        try {
            if (!node.isPassword) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val hit = if (strictCenter) rect.contains(bounds.centerX(), bounds.centerY())
                    else Rect.intersects(rect, bounds)
                if (hit) {
                    val t = postTextOf(node)
                    if (t != null) out.add(Triple(bounds.top, bounds.left, t))
                }
            }
            for (i in 0 until node.childCount) {
                collectRegion2(node.getChild(i), rect, out, depth + 1, strictCenter)
            }
        } catch (e: Exception) {
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: LinkedHashSet<String>, depth: Int) {
        if (node == null || depth > 30) return
        try {
            val t = postTextOf(node)
            if (t != null) out.add(t)
            for (i in 0 until node.childCount) {
                collect(node.getChild(i), out, depth + 1)
            }
        } catch (e: Exception) {
        }
    }
}
