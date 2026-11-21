package com.auto.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.charset.StandardCharsets
import android.os.Handler
import android.os.Looper

// 任务实体
data class TaskItem(
    val text: String,
    val char_min: Double,
    val char_max: Double,
    val auto_send: Boolean
)

data class QueueBean(
    var queue: MutableList<TaskItem> = mutableListOf()
)

class AutoKeyboardIME : InputMethodService() {
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val queueFile = File("/storage/emulated/0/QQReply/banks/msg_queue.json")
    private var isRunning = false

    override fun onCreateInputView(): View {
        // 简单键盘布局，你们后面可以替换成商用样式键盘
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val btnStart = Button(this)
        btnStart.text = "▶ 启动自动发送队列"
        btnStart.setOnClickListener {
            if (!isRunning) {
                consumeQueue()
            }
        }

        val btnClear = Button(this)
        btnClear.text = "清空任务队列"
        btnClear.setOnClickListener {
            clearQueue()
        }

        root.addView(btnStart)
        root.addView(btnClear)
        return root
    }

    // 读取整个队列
    private fun loadQueue(): QueueBean {
        return try {
            if (!queueFile.exists()) return QueueBean()
            val json = queueFile.readText(StandardCharsets.UTF_8)
            gson.fromJson(json, object : TypeToken<QueueBean>() {}.type)
        } catch (e: Exception) {
            QueueBean()
        }
    }

    // 保存队列
    private fun saveQueue(bean: QueueBean) {
        try {
            queueFile.writeText(gson.toJson(bean), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    // 清空队列
    private fun clearQueue() {
        saveQueue(QueueBean())
    }

    // 消费队列入口
    private fun consumeQueue() {
        isRunning = true
        val bean = loadQueue()
        if (bean.queue.isEmpty()) {
            isRunning = false
            return
        }
        val firstTask = bean.queue.removeAt(0)
        saveQueue(bean)
        typeTextOneByOne(firstTask)
    }

    // 逐字模拟打字 + 随机间隔
    private fun typeTextOneByOne(task: TaskItem) {
        val chars = task.text.toCharArray()
        var index = 0
        fun nextChar() {
            if (index >= chars.size) {
                // 文字打完，是否自动回车发送
                if (task.auto_send) {
                    sendEnter()
                }
                // 继续消费下一条任务
                handler.postDelayed({
                    consumeQueue()
                }, 300)
                return
            }
            val c = chars[index]
            currentInputConnection?.commitText(c.toString(), 1)
            index++
            val delay = ((task.char_max - task.char_min) * Math.random() + task.char_min) * 1000
            handler.postDelayed({ nextChar() }, delay.toLong())
        }
        nextChar()
    }

    // 发送回车
    private fun sendEnter() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
    }
}
