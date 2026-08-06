package com.suishouban.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.suishouban.app.AppUiState
import com.suishouban.app.data.model.ActionCard
import com.suishouban.app.data.model.WorkspaceTypes
import com.suishouban.app.ui.screens.CardWorkspaceTab
import com.suishouban.app.ui.screens.CardsScreen
import com.suishouban.app.ui.theme.SuiShouBanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CardsTeamNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workspaceTabsFilterCardsAndOpenTeamManagement() {
        var selectedTab by mutableStateOf(CardWorkspaceTab.PERSONAL)
        var managementOpened = false
        val state = AppUiState(
            cards = listOf(
                ActionCard(id = "personal-card", title = "Personal acceptance card"),
                ActionCard(
                    id = "team-card",
                    title = "Team acceptance card",
                    workspaceType = WorkspaceTypes.TEAM,
                    workspaceId = "team-1",
                ),
            ),
        )

        compose.setContent {
            SuiShouBanTheme {
                CardsScreen(
                    state = state,
                    onUpdate = {},
                    onComplete = {},
                    onArchive = {},
                    onImport = {},
                    workspaceTab = selectedTab,
                    onWorkspaceTabChange = { selectedTab = it },
                    onManageTeams = { managementOpened = true },
                    teamCount = 1,
                )
            }
        }

        compose.onNodeWithTag("cards_tab_personal").assertIsDisplayed()
        compose.onNodeWithText("Personal acceptance card").assertIsDisplayed()
        compose.onNodeWithText("Team acceptance card").assertDoesNotExist()

        compose.onNodeWithTag("cards_tab_team").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Personal acceptance card").assertDoesNotExist()
        compose.onNodeWithText("Team acceptance card").assertIsDisplayed()
        compose.onNodeWithTag("cards_manage_teams").assertIsDisplayed().performClick()

        compose.runOnIdle { assertTrue(managementOpened) }
    }

    @Test
    fun workspaceTabsKeepIndependentSearchState() {
        var selectedTab by mutableStateOf(CardWorkspaceTab.PERSONAL)

        compose.setContent {
            SuiShouBanTheme {
                CardsScreen(
                    state = AppUiState(),
                    onUpdate = {},
                    onComplete = {},
                    onArchive = {},
                    onImport = {},
                    workspaceTab = selectedTab,
                    onWorkspaceTabChange = { selectedTab = it },
                )
            }
        }

        compose.onNodeWithTag("cards_search_personal").performTextInput("个人检索")
        compose.onNodeWithTag("cards_tab_team").performClick()
        compose.onNodeWithTag("cards_search_team").performTextInput("团队检索")
        compose.onNodeWithTag("cards_tab_personal").performClick()
        compose.onNodeWithTag("cards_search_personal").assertTextEquals("个人检索")
        compose.onNodeWithTag("cards_tab_team").performClick()
        compose.onNodeWithTag("cards_search_team").assertTextEquals("团队检索")
    }

    @Test
    fun selectedWorkspaceSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)

        restoration.setContent {
            var selectedTab by rememberSaveable { mutableStateOf(CardWorkspaceTab.PERSONAL) }
            SuiShouBanTheme {
                CardsScreen(
                    state = AppUiState(),
                    onUpdate = {},
                    onComplete = {},
                    onArchive = {},
                    onImport = {},
                    workspaceTab = selectedTab,
                    onWorkspaceTabChange = { selectedTab = it },
                )
            }
        }

        compose.onNodeWithTag("cards_tab_team").performClick()
        compose.onNodeWithTag("cards_manage_teams").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cards_manage_teams").assertIsDisplayed()
    }
}
