package com.opirl.app.control

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.ControllerAction
import com.opirl.app.settings.ControllerFunction
import com.opirl.app.settings.GamepadButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val AXIS_PRESSED = 0.5f

fun gamepadButton(keyCode: Int): GamepadButton? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> GamepadButton.A
    KeyEvent.KEYCODE_BUTTON_B -> GamepadButton.B
    KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X
    KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.L1
    KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.R1
    KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.L2
    KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.R2
    KeyEvent.KEYCODE_DPAD_UP -> GamepadButton.DPAD_UP
    KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButton.DPAD_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButton.DPAD_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButton.DPAD_RIGHT
    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> GamepadButton.MENU
    else -> null
}

fun axisButtons(hatX: Float, hatY: Float, leftTrigger: Float, rightTrigger: Float): Set<GamepadButton> = buildSet {
    if (hatX <= -AXIS_PRESSED) add(GamepadButton.DPAD_LEFT)
    if (hatX >= AXIS_PRESSED) add(GamepadButton.DPAD_RIGHT)
    if (hatY <= -AXIS_PRESSED) add(GamepadButton.DPAD_UP)
    if (hatY >= AXIS_PRESSED) add(GamepadButton.DPAD_DOWN)
    if (leftTrigger >= AXIS_PRESSED) add(GamepadButton.L2)
    if (rightTrigger >= AXIS_PRESSED) add(GamepadButton.R2)
}

fun keyboardKey(unicodeChar: Int, control: Boolean, meta: Boolean): String? =
    if (unicodeChar <= 0 || control || meta) null else String(Character.toChars(unicodeChar))

private fun isGamepad(sources: Int) =
    sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

class Controls(
    context: Context,
    private val settings: () -> AppSettings,
    private val perform: (ControllerAction, Boolean) -> Unit,
    private val onGamepad: (connected: Boolean, number: Int) -> Unit,
) : InputManager.InputDeviceListener {
    private val input = context.getSystemService(InputManager::class.java)
    private val pressed = HashMap<Int, Set<GamepadButton>>()
    private val keyHeld = HashMap<Int, Set<GamepadButton>>()
    private val axisHeld = HashMap<Int, Set<GamepadButton>>()
    private val heldKeys = HashSet<String>()
    private val gamepads = LinkedHashSet<Int>()
    private val _gamepadCount = MutableStateFlow(0)
    val gamepadCount: StateFlow<Int> = _gamepadCount.asStateFlow()

    fun start() {
        input.registerInputDeviceListener(this, null)
        input.inputDeviceIds.forEach(::add)
    }

    private fun add(deviceId: Int): Boolean {
        val device = input.getInputDevice(deviceId) ?: return false
        if (device.isVirtual || !isGamepad(device.sources) || !gamepads.add(deviceId)) return false
        _gamepadCount.value = gamepads.size
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (add(deviceId)) onGamepad(true, gamepads.size)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val number = gamepads.indexOf(deviceId) + 1
        if (!gamepads.remove(deviceId)) return
        keyHeld.remove(deviceId)
        axisHeld.remove(deviceId)
        update(deviceId)
        pressed.remove(deviceId)
        _gamepadCount.value = gamepads.size
        onGamepad(false, number)
    }

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    private fun action(button: GamepadButton): ControllerAction? =
        settings().gamepad[button]?.takeIf { it.function != ControllerFunction.UNUSED }

    private fun update(deviceId: Int): Boolean {
        val now = keyHeld[deviceId].orEmpty() + axisHeld[deviceId].orEmpty()
        val before = pressed[deviceId].orEmpty()
        pressed[deviceId] = now
        var handled = false
        for (button in now - before) {
            action(button)?.let {
                perform(it, true)
                handled = true
            }
        }
        for (button in before - now) {
            action(button)?.let {
                perform(it, false)
                handled = true
            }
        }
        return handled
    }

    fun onGamepadKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.source) && event.device?.let { isGamepad(it.sources) } != true) return false
        val button = gamepadButton(event.keyCode) ?: return false
        val held = keyHeld[event.deviceId].orEmpty()
        keyHeld[event.deviceId] = when (event.action) {
            KeyEvent.ACTION_DOWN -> held + button
            KeyEvent.ACTION_UP -> held - button
            else -> return false
        }
        update(event.deviceId)
        return action(button) != null
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.source) || event.action != MotionEvent.ACTION_MOVE) return false
        val now = axisButtons(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
        )
        if (now == axisHeld[event.deviceId].orEmpty()) return false
        axisHeld[event.deviceId] = now
        return update(event.deviceId)
    }

    fun onKeyboardKey(event: KeyEvent, down: Boolean): Boolean {
        if (isGamepad(event.source)) return false
        val key = keyboardKey(event.unicodeChar, event.isCtrlPressed, event.isMetaPressed) ?: return false
        val bound = settings().keyboard.firstOrNull { it.key == key && it.action.function != ControllerFunction.UNUSED } ?: return false
        if (if (down) heldKeys.add(key) else heldKeys.remove(key)) perform(bound.action, down)
        return true
    }
}
