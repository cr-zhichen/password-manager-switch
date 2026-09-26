package cn.zgccrui.passwordswitch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateState(
    val checking: Boolean = false,
    val available: AppUpdate? = null,
    val message: String = "",
    val automatic: Boolean = false,
    val failed: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("updates", 0)
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private val checker = UpdateChecker()
    private var checkJob: Job? = null
    private var manualFeedback = false

    fun check(manual: Boolean = false) {
        if (checkJob?.isActive == true) {
            if (manual) {
                manualFeedback = true
                mutableState.value = mutableState.value.copy(automatic = false)
            }
            return
        }
        val now = System.currentTimeMillis()
        val lastAttempt = preferences.getLong("last_attempt", 0)
        val sameVersion = preferences.getString("checked_version", null) == BuildConfig.VERSION_NAME
        if (!manual && sameVersion && now >= lastAttempt && now - lastAttempt < CHECK_INTERVAL) return

        manualFeedback = manual
        preferences.edit().putLong("last_attempt", now)
            .putString("checked_version", BuildConfig.VERSION_NAME).apply()
        mutableState.value = mutableState.value.copy(checking = true, message = "", automatic = !manual, failed = false)
        checkJob = viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { checker.check(BuildConfig.VERSION_NAME) }
                mutableState.value = UpdateState(
                    available = update,
                    message = if (manualFeedback && update == null) "未发现更新版本" else "",
                    automatic = !manualFeedback,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = if (failure is UpdateCheckFailure) failure.message else null
                mutableState.value = mutableState.value.copy(
                    checking = false,
                    message = if (manualFeedback) message ?: "检查更新失败，请检查网络后重试。" else "",
                    automatic = !manualFeedback,
                    failed = manualFeedback,
                )
            }
        }
    }

    companion object {
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    }
}
