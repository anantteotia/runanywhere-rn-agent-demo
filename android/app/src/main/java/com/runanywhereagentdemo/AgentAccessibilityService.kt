package com.runanywhereagentdemo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

  companion object {
    private const val TAG = "AgentAccessibilityService"
    @Volatile var instance: AgentAccessibilityService? = null

    fun isEnabled(context: Context): Boolean {
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: return false
      return enabledServices.contains("${context.packageName}/${AgentAccessibilityService::class.java.name}")
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    serviceInfo = serviceInfo.apply {
      eventTypes = AccessibilityEvent.TYPES_ALL_MASK
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    Log.i(TAG, "Accessibility service connected")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No-op: agent pulls state on demand.
  }

  override fun onInterrupt() {
    // No-op
  }

  fun getInteractiveElementsJson(): String {
    val root = rootInActiveWindow ?: return "[]"
    val results = JSONArray()
    traverseNode(root, results)
    return results.toString()
  }

  private fun traverseNode(node: AccessibilityNodeInfo, results: JSONArray) {
    val text = node.text?.toString()?.trim().orEmpty()
    val desc = node.contentDescription?.toString()?.trim().orEmpty()
    val clickable = node.isClickable
    val editable = node.isEditable
    val focusable = node.isFocusable
    val enabled = node.isEnabled
    val className = node.className?.toString().orEmpty()
    val viewId = node.viewIdResourceName.orEmpty()

    if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      val centerX = (bounds.left + bounds.right) / 2
      val centerY = (bounds.top + bounds.bottom) / 2

      val obj = JSONObject()
      obj.put("text", text)
      obj.put("contentDescription", desc)
      obj.put("clickable", clickable)
      obj.put("editable", editable)
      obj.put("focusable", focusable)
      obj.put("enabled", enabled)
      obj.put("className", className)
      obj.put("viewId", viewId)
      obj.put("bounds", JSONObject().apply {
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
      })
      obj.put("center", JSONArray().put(centerX).put(centerY))
      results.put(obj)
    }

    for (i in 0 until node.childCount) {
      node.getChild(i)?.let { child ->
        traverseNode(child, results)
      }
    }
  }

  fun findEditableNode(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    return findNode(root) { it.isEditable }
  }

  private fun findNode(
    node: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean
  ): AccessibilityNodeInfo? {
    if (predicate(node)) return node
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = findNode(child, predicate)
      if (found != null) return found
    }
    return null
  }

  fun pressEnter() {
    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
  }
}
