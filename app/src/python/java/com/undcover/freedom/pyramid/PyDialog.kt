package com.undcover.freedom.pyramid

import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

import com.chaquo.python.PyObject
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.AppLog

import org.json.JSONObject

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 暴露给 Python 脚本的原生界面能力。
 *
 * python 侧通过 PyBridge 注入后可以直接调用：
 *   r = dialog({'title': '登录', 'inputs': [{'id': 'phone', 'name': '手机号'}],
 *               'buttons': ['确定', '取消']})
 *   r['button'] / r['values']['phone']
 *
 * 与 js 的 dialog 一样做成同步阻塞：python 线程等主线程弹窗结果，两边靠队列交接。
 * 主线程被调用时直接放弃（post 出去的弹窗永远不会执行，只能死锁）。
 */
class PyDialog {

    companion object {

        private const val TIMEOUT_MS = 300_000L

        @JvmStatic
        fun dialog(specJson: String?): String? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                AppLog.i("PyDialog: ignored on main thread")
                return null
            }
            val act = try {
                ActivityStack.getInstance().currentActivity()
            } catch (th: Throwable) {
                null
            } ?: return null
            if (act.isFinishing || act.isDestroyed) return null
            val spec = try {
                JSONObject(specJson ?: "")
            } catch (th: Throwable) {
                JSONObject()
            }

            val queue = LinkedBlockingQueue<PyDialogEvent>()
            val main = Handler(Looper.getMainLooper())
            val edits = ArrayList<Pair<List<String>, EditText>>()
            val dlgRef = AtomicReference<android.app.AlertDialog?>(null)

            main.post {
                try {
                    val pad = (20 * act.resources.displayMetrics.density).toInt()
                    val wrap = LinearLayout(act)
                    wrap.orientation = LinearLayout.VERTICAL
                    wrap.setPadding(pad, pad / 2, pad, 0)
                    val tv = TextView(act)
                    tv.text = spec.optString("message")
                    tv.setTextColor(0xFFCCCCCC.toInt())
                    wrap.addView(tv)

                    val inputs = spec.optJSONArray("inputs")
                    val inputCount = inputs?.length() ?: 0
                    for (i in 0 until inputCount) {
                        val item = inputs!!.opt(i)
                        val jo = item as? JSONObject
                        val keys = valueKeys(item, i)
                        val et = EditText(act)
                        et.hint = keys.firstOrNull() ?: "input$i"
                        et.setSingleLine(true)
                        et.setTextColor(0xFFFFFFFF.toInt())
                        et.setHintTextColor(0xFF888888.toInt())
                        val preset = jo?.optString("value") ?: ""
                        if (preset.isNotEmpty()) et.setText(preset)
                        val type = jo?.optString("type")?.lowercase() ?: ""
                        if (type.contains("password") || type.contains("pwd")) {
                            et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                        wrap.addView(et)
                        edits.add(Pair(keys, et))
                    }

                    val buttons = spec.optJSONArray("buttons")
                    val buttonCount = buttons?.length() ?: 0
                    val names = ArrayList<String>()
                    val ids = ArrayList<String?>()
                    for (i in 0 until buttonCount) {
                        val item = buttons!!.opt(i)
                        names.add(labelOf(item, i))
                        ids.add(idOf(item))
                    }
                    if (names.isEmpty()) {
                        names.add("确定")
                        ids.add(null)
                        names.add("取消")
                        ids.add(null)
                    }
                    val fire: (Int) -> Unit = { idx ->
                        val vals = LinkedHashMap<String, String>()
                        for (entry in edits) {
                            val text = entry.second.text.toString()
                            for (k in entry.first) vals[k] = text
                        }
                        queue.offer(PyDialogEvent(names[idx], ids.getOrNull(idx), vals, false))
                    }
                    val builder = android.app.AlertDialog.Builder(act)
                        .setTitle(spec.optString("title"))
                        .setView(wrap)
                    if (names.size > 0) builder.setPositiveButton(names[0]) { _, _ -> fire(0) }
                    if (names.size > 1) builder.setNegativeButton(names[1]) { _, _ -> fire(1) }
                    if (names.size > 2) builder.setNeutralButton(names[2]) { _, _ -> fire(2) }
                    builder.setOnCancelListener {
                        queue.offer(PyDialogEvent(null, null, emptyMap(), true))
                    }
                    dlgRef.set(builder.show())
                } catch (th: Throwable) {
                    AppLog.e("PyDialog", th)
                    queue.offer(PyDialogEvent(null, null, emptyMap(), true))
                }
            }

            val ev = try {
                queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (th: Throwable) {
                null
            }
            main.post { dismissQuietly(dlgRef) }
            if (ev == null || ev.dismissed || ev.button == null) return null
            val out = JSONObject()
            out.put("button", ev.button)
            if (ev.buttonId != null) out.put("id", ev.buttonId)
            val vals = JSONObject()
            for ((key, value) in ev.values) vals.put(key, value)
            out.put("values", vals)
            return out.toString()
        }

        /** 给自建原生界面的脚本（如反射拿 Activity 的那类）一个可靠的入口 */
        @JvmStatic
        fun getActivity(): Any? {
            return try {
                val act = ActivityStack.getInstance().currentActivity()
                if (act.isFinishing || act.isDestroyed) null else act
            } catch (th: Throwable) {
                null
            }
        }

        /** 在主线程执行一段 python 回调，返回其返回值 */
        @JvmStatic
        fun runOnUi(fn: PyObject?): Any? {
            if (fn == null) return null
            if (Looper.myLooper() == Looper.getMainLooper()) return fn.call()
            val slot = AtomicReference<Any?>(null)
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val lock = Object()
            Handler(Looper.getMainLooper()).post {
                try {
                    slot.set(fn.call())
                } catch (th: Throwable) {
                    AppLog.e("PyDialog", th)
                } finally {
                    done.set(true)
                    synchronized(lock) { lock.notifyAll() }
                }
            }
            val deadline = System.currentTimeMillis() + 30_000L
            synchronized(lock) {
                while (!done.get()) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    try {
                        lock.wait(left)
                    } catch (th: Throwable) {
                        break
                    }
                }
            }
            return if (done.get()) slot.get() else null
        }

        private fun dismissQuietly(ref: AtomicReference<android.app.AlertDialog?>) {
            try {
                ref.get()?.dismiss()
            } catch (th: Throwable) {
                // 窗口已经没了，忽略
            }
        }

        private fun valueKeys(item: Any?, index: Int): List<String> {
            val keys = ArrayList<String>()
            fun add(v: String?) {
                if (!v.isNullOrEmpty() && !keys.contains(v)) keys.add(v)
            }
            if (item is JSONObject) {
                for (k in arrayOf("id", "name", "title", "label", "text", "key", "field")) {
                    add(item.optString(k))
                }
            } else if (item != null) {
                add(item.toString())
            }
            if (keys.isEmpty()) add("input$index")
            return keys
        }

        private fun labelOf(item: Any?, index: Int): String {
            if (item is JSONObject) {
                for (k in arrayOf("name", "title", "label", "text", "placeholder", "hint", "id", "key")) {
                    val v = item.optString(k)
                    if (v.isNotEmpty()) return v
                }
            } else if (item != null) {
                val s = item.toString()
                if (s.isNotEmpty()) return s
            }
            return "input$index"
        }

        private fun idOf(item: Any?): String? {
            if (item !is JSONObject) return null
            val v = item.optString("id")
            return if (v.isEmpty()) null else v
        }
    }

    private class PyDialogEvent(
        val button: String?,
        val buttonId: String?,
        val values: Map<String, String>,
        val dismissed: Boolean,
    )
}
