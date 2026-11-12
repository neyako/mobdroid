package com.opirl.app.scenes

import com.opirl.app.settings.AutoSwitcher
import com.opirl.app.settings.SwitcherScene
import kotlin.random.Random

class AutoSceneSwitcher(private val random: Random = Random.Default) {
    private val queue = ArrayList<String>()
    private var current: String? = null

    fun next(switcher: AutoSwitcher, exists: (String) -> Boolean): SwitcherScene? {
        repeat(2) {
            fill(switcher)
            while (queue.isNotEmpty()) {
                val id = queue.removeAt(queue.lastIndex)
                val scene = switcher.scenes.firstOrNull { it.id == id } ?: continue
                if (scene.sceneId == null || !exists(scene.sceneId)) continue
                current = id
                return scene
            }
        }
        return null
    }

    private fun fill(switcher: AutoSwitcher) {
        if (queue.isNotEmpty()) return
        queue += switcher.scenes.map { it.id }.reversed()
        if (!switcher.shuffle) return
        queue.shuffle(random)
        if (queue.size > 1 && queue.last() == current) queue.add(0, queue.removeAt(queue.lastIndex))
    }
}
