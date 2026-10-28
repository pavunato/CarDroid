package com.cardroid.app.car

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Architecture guard: external activities must never replace the launcher/rail. */
class EmbeddedLauncherRegressionTest {
    private fun source(name: String) = File("src/main/java/com/cardroid/app/$name").readText()

    @Test fun receiverAppSelectionClosesLauncherOverlays() {
        assertTrue(source("car/CarLauncherUi.kt").contains("LaunchedEffect(appTarget)"))
        assertTrue(source("car/RequestUiApps.kt").contains("CarDisplayState.launch(pkg)"))
    }

    @Test fun helperStatusRefreshCannotLaunchDuplicatePanelTasks() {
        assertTrue(source("car/CarNativePanels.kt").contains("if (appLaunched) return"))
    }

    @Test fun embeddedTaskAndInputAreIsolatedFromDashboard() {
        assertTrue("Panel tasks must not reuse a dashboard task", source("helper/activity/ActivityLauncher.kt").contains("FLAG_ACTIVITY_MULTIPLE_TASK"))
        assertTrue("Single-app background must consume hits above dashboard", source("car/CarLauncherUi.kt").contains("onClickLabel = \"Embedded app background\""))
    }

    @Test fun updateEntryPointsAreWired() {
        val setup = source("MainActivity.kt")
        assertTrue(setup.contains("UpdateChecks.checkAutomatically(this)"))
        assertTrue(setup.contains("UpdateActivity::class.java"))
    }

    @Test fun helperSetupDoesNotRequestAccessibilityOrFloatingHome() {
        val setup = source("MainActivity.kt")
        assertFalse("Helper routes touch without Accessibility", setup.contains("ChecklistRow(\"Touch control\""))
        assertFalse("Persistent rail replaces floating Home", source("RemoteControlService.kt").contains("cornerHome.start()"))
    }

    @Test fun recentsReopenEmbeddedAppsNotOuterTasks() {
        val ui = source("car/CarLauncherUi.kt")
        assertFalse("Task promotion can cover the rail", ui.contains("CarDisplayState.moveTaskToFront"))
        assertTrue(ui.contains("recentAppTargets"))
    }

    @Test fun embeddedContentKeepsRailAndDisablesCoveredDashboardInput() {
        val ui = source("car/CarLauncherUi.kt")
        assertTrue("Single app must use the existing native display panel", ui.contains("NativeAppPanel(selectedApp"))
        assertTrue("Covered dashboard must not receive input", ui.contains("contentVisible = selectedApp == null && overlay == Overlay.None"))
        assertTrue("Back must reach the embedded display", ui.contains("CarDisplayState.panelBack"))
    }

    @Test fun normalAppLaunchSelectsLauncherContentInsteadOfStartingActivity() {
        val apps = source("car/CarApps.kt")
        assertFalse("Direct activity launch covers the persistent sidebar", apps.contains("activity.startActivity("))
        assertTrue("App opens must select embedded content", apps.contains("CarDisplayState.openApp(app.target)"))
    }
}
