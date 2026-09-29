package com.mobai.jm.util

import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * 崩溃自测（开发者模式专用）：
 * 随机触发一种"真实代码缺陷"式崩溃，用来验证崩溃兜底：
 * 崩溃提示页是否弹出、剪贴板是否自动复制、crash.log 是否落盘。
 *
 * 覆盖的典型崩溃形态：空指针 / 数组越界 / 除零 / 类型转换 / 并发修改 / 状态断言。
 */
object CrashTester {

    /** 随机触发一种崩溃（主线程抛出，与真实 UI 崩溃路径一致） */
    fun triggerRandomCrash() {
        Handler(Looper.getMainLooper()).post {
            when (Random.nextInt(0, 6)) {
                0 -> npeCrash()
                1 -> indexCrash()
                2 -> divideCrash()
                3 -> castCrash()
                4 -> concurrentModifyCrash()
                else -> stateCrash()
            }
        }
    }

    /** ① 空指针：可空列表直接解引用 */
    private fun npeCrash() {
        DiagLog.w("crash-test: 触发 空指针(NPE)")
        val items: List<String>? = null
        items!!.first()
    }

    /** ② 数组越界 */
    private fun indexCrash() {
        DiagLog.w("crash-test: 触发 数组越界(IndexOutOfBounds)")
        val data = IntArray(8)
        val idx = data.size + 3
        println(data[idx])
    }

    /** ③ 除零 */
    private fun divideCrash() {
        DiagLog.w("crash-test: 触发 除零(ArithmeticException)")
        val total = 0
        val n = 10
        println(n / total)
    }

    /** ④ 强制类型转换 */
    private fun castCrash() {
        DiagLog.w("crash-test: 触发 类型转换(ClassCastException)")
        val value: Any = "禁漫姬"
        val num: Int = value as Int
        println(num)
    }

    /** ⑤ 边遍历边修改集合 */
    private fun concurrentModifyCrash() {
        DiagLog.w("crash-test: 触发 并发修改(ConcurrentModificationException)")
        val list = mutableListOf(1, 2, 3)
        for (item in list) {
            list.add(item * 2)
        }
    }

    /** ⑥ 状态断言失败（业务里最常见的崩溃形态） */
    private fun stateCrash() {
        DiagLog.w("crash-test: 触发 状态断言(IllegalStateException)")
        val state = "idle"
        check(state == "ready") { "状态机异常：当前状态=$state" }
    }
}
