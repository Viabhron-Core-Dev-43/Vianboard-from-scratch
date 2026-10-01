package com.example

import android.app.Application
import com.example.ime.VianBoardService
import com.example.ime.keyboard.VianKeyboardView
import com.example.ime.settings.AppearanceSettingsActivity
import com.example.ime.settings.SettingsActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashInvestigationTest {

    @Test
    fun testAllActivitiesInflation() {
        val activities = listOf(
            com.example.MainActivity::class.java,
            com.example.ime.setup.SetupWizardActivity::class.java,
            com.example.ime.settings.SettingsActivity::class.java,
            com.example.ime.settings.AppearanceActivity::class.java,
            com.example.ime.settings.MainLayoutCustomizationActivity::class.java,
            com.example.ime.settings.AppearanceSettingsActivity::class.java,
            com.example.ime.settings.LayoutCustomizationActivity::class.java,
            com.example.ime.settings.ToolbarSettingsActivity::class.java,
            com.example.ime.settings.QuickNotesSettingsActivity::class.java,
            com.example.ime.settings.DesktopShortcutsSettingsActivity::class.java,
            com.example.ime.settings.VoiceInputSettingsActivity::class.java,
            com.example.ime.settings.SecurityVaultSettingsActivity::class.java,
            com.example.ime.settings.AdvancedSettingsActivity::class.java,
            com.example.ime.settings.TextEngineSettingsActivity::class.java,
            com.example.ime.settings.PersonalDictionarySettingsActivity::class.java,
            com.example.ime.settings.BackupRestoreSettingsActivity::class.java
        )
        for (actClass in activities) {
            println("Testing activity: ${actClass.simpleName}")
            val controller = Robolectric.buildActivity(actClass).setup()
            assertNotNull(controller.get())
        }
    }

    @Test
    fun testVianBoardServiceFullLifecycleAndDraw() {
        println("=== TESTING VianBoardService Lifecycle & Draw ===")
        val controller = Robolectric.buildService(VianBoardService::class.java).create()
        val service = controller.get()
        val inputView = service.onCreateInputView()
        val editorInfo = android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(editorInfo, false)
        service.onStartInputView(editorInfo, false)

        val kv = (inputView as android.view.ViewGroup).getChildAt(0) as VianKeyboardView
        assertNotNull("KeyboardView should not be null", kv)
        kv?.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.AT_MOST)
        )
        kv?.layout(0, 0, 1080, kv.measuredHeight)

        val bmp = android.graphics.Bitmap.createBitmap(1080, kv!!.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        kv.draw(canvas)
        println("VianBoardService keyboardView drawn successfully, height: ${kv.measuredHeight}")
    }

    @Test
    fun testSetupUtilsDefensiveMethods() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        // Must never throw exceptions even if services return unusual states
        val isEnabled = com.example.ime.setup.SetupUtils.isKeyboardEnabled(app)
        val isSelected = com.example.ime.setup.SetupUtils.isKeyboardSelected(app)
        com.example.ime.setup.SetupUtils.showInputMethodPicker(app)
        println("SetupUtils verified: isEnabled=$isEnabled, isSelected=$isSelected")
    }

    @Test
    fun testSettingsActivityIntentResolution() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            setPackage(app.packageName)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        val resolveInfo = app.packageManager.resolveActivity(intent, 0)
        assertNotNull("SettingsActivity or MainActivity must resolve for ACTION_MAIN + DEFAULT", resolveInfo)
    }
}
