package org.thoughtcrime.securesms.conversation.expiration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.preferences.ExpirationRadioOption
import org.thoughtcrime.securesms.preferences.RadioOption
import org.thoughtcrime.securesms.preferences.radioOption
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
class ExpirationSettingsViewModel(
    private val threadId: Long,
    private val afterReadOptions: List<ExpirationRadioOption>,
    private val afterSendOptions: List<ExpirationRadioOption>,
    private val textSecurePreferences: TextSecurePreferences,
    private val messageExpirationManager: MessageExpirationManagerProtocol,
    private val threadDb: ThreadDatabase,
    private val groupDb: GroupDatabase,
    private val storage: Storage
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpirationSettingsUiState())
    val uiState: StateFlow<ExpirationSettingsUiState> = _uiState

    private var expirationConfig: ExpirationConfiguration? = null

    private val _selectedExpirationType: MutableStateFlow<ExpiryMode> = MutableStateFlow(ExpiryMode.NONE)
    val selectedExpirationType: StateFlow<ExpiryMode> = _selectedExpirationType

    private val _selectedExpirationTimer = MutableStateFlow(afterSendOptions.firstOrNull())
    val selectedExpirationTimer: StateFlow<RadioOption<ExpiryMode>?> = _selectedExpirationTimer

    private val _expirationTimerOptions = MutableStateFlow<List<RadioOption<ExpiryMode>>>(emptyList())
    val expirationTimerOptions: StateFlow<List<RadioOption<ExpiryMode>>> = _expirationTimerOptions

    init {
        // SETUP
        viewModelScope.launch {
            expirationConfig = storage.getExpirationConfiguration(threadId)
            val expirationType = expirationConfig?.expiryMode
            val recipient = threadDb.getRecipientForThreadId(threadId)
            val groupInfo = recipient?.takeIf { it.isClosedGroupRecipient }
                ?.run { address.toGroupString().let(groupDb::getGroup).orNull() }
            _uiState.update { currentUiState ->
                currentUiState.copy(
                    isSelfAdmin = groupInfo == null || groupInfo.admins.any{ it.serialize() == textSecurePreferences.getLocalNumber() },
                    showExpirationTypeSelector = true,
                    recipient = recipient
                )
            }
            _selectedExpirationType.value = if (ExpirationConfiguration.isNewConfigEnabled) {
                expirationType ?: ExpiryMode.NONE
            } else {
                if (expirationType != null && expirationType != ExpiryMode.NONE)
                    ExpiryMode.Legacy(expirationType.expirySeconds)
                else ExpiryMode.NONE
            }
            _selectedExpirationTimer.value = when(expirationType) {
                is ExpiryMode.AfterSend -> afterSendOptions.find { it.value == expirationType }
                is ExpiryMode.AfterRead -> afterReadOptions.find { it.value == expirationType }
                else -> afterSendOptions.firstOrNull()
            }
        }
//        selectedExpirationType.mapLatest {
//            when (it) {
//                is ExpiryMode.Legacy, is ExpiryMode.AfterSend -> afterSendOptions
//                is ExpiryMode.AfterRead -> afterReadOptions
//                else -> emptyList()
//            }
//        }.onEach { options ->
//            val enabled = _uiState.value.isSelfAdmin || recipient.value?.isClosedGroupRecipient == true
//            _expirationTimerOptions.value = options.let {
//                if (ExpirationConfiguration.isNewConfigEnabled && recipient.value?.run { isLocalNumber || isClosedGroupRecipient } == true) it.drop(1) else it
//            }.map { it.copy(enabled = enabled) }
//        }.launchIn(viewModelScope)
    }

    fun onExpirationTypeSelected(option: RadioOption<ExpiryMode>) {
        _selectedExpirationType.value = option.value
        _selectedExpirationTimer.value = _expirationTimerOptions.value.firstOrNull()
    }

    fun onExpirationTimerSelected(option: RadioOption<ExpiryMode>) {
        _selectedExpirationTimer.value = option
    }

    private fun KClass<out ExpiryMode>?.withTime(expirationTimer: Long) = when(this) {
        ExpiryMode.AfterRead::class -> ExpiryMode.AfterRead(expirationTimer)
        ExpiryMode.AfterSend::class -> ExpiryMode.AfterSend(expirationTimer)
        else -> ExpiryMode.NONE
    }

    fun onSetClick() = viewModelScope.launch {
        val state = uiState.value
        val expiryMode = _selectedExpirationTimer.value?.value ?: ExpiryMode.NONE
        val typeValue = expiryMode.let {
            if (it is ExpiryMode.Legacy) ExpiryMode.AfterRead(it.expirySeconds)
            else it
        }
        val address = state.recipient?.address
        if (address == null || expirationConfig?.expiryMode == typeValue) {
            _uiState.update {
                it.copy(settingsSaved = false)
            }
            return@launch
        }

        val expiryChangeTimestampMs = SnodeAPI.nowWithOffset
        storage.setExpirationConfiguration(ExpirationConfiguration(threadId, typeValue, expiryChangeTimestampMs))

        val message = ExpirationTimerUpdate(typeValue.expirySeconds.toInt())
        message.sender = textSecurePreferences.getLocalNumber()
        message.recipient = address.serialize()
        message.sentTimestamp = expiryChangeTimestampMs
        messageExpirationManager.setExpirationTimer(message, typeValue)

        MessageSender.send(message, address)
        _uiState.update {
            it.copy(settingsSaved = true)
        }
    }

    fun getDeleteOptions(): List<ExpirationRadioOption> {
        if (!uiState.value.showExpirationTypeSelector) return emptyList()

        val recipient = uiState.value.recipient ?: return emptyList()

        return if (ExpirationConfiguration.isNewConfigEnabled) when {
            recipient.isLocalNumber -> noteToSelfOptions()
            recipient.isContactRecipient -> contactRecipientOptions()
            recipient.isClosedGroupRecipient -> closedGroupRecipientOptions()
            else -> emptyList()
        } else when {
            recipient.isContactRecipient && !recipient.isLocalNumber -> oldConfigContactRecipientOptions()
            else -> oldConfigDefaultOptions()
        }
    }

    private fun oldConfigDefaultOptions() = listOf(
        radioOption(ExpiryMode.NONE, R.string.expiration_off),
        radioOption(ExpiryMode.Legacy(0), R.string.expiration_type_disappear_legacy) {
            subtitle(R.string.expiration_type_disappear_legacy_description)
        },
        radioOption(ExpiryMode.AfterSend(0), R.string.expiration_type_disappear_after_send) {
            subtitle(R.string.expiration_type_disappear_after_send_description)
            enabled = false
            contentDescription(R.string.AccessibilityId_disappear_after_send_option)
        }
    )

    private fun oldConfigContactRecipientOptions() = listOf(
        radioOption(ExpiryMode.NONE, R.string.expiration_off) {
            contentDescription(R.string.AccessibilityId_disable_disappearing_messages)
        },
        radioOption(ExpiryMode.Legacy(0), R.string.expiration_type_disappear_legacy) {
            subtitle(R.string.expiration_type_disappear_legacy_description)
        },
        radioOption(ExpiryMode.AfterRead(0), R.string.expiration_type_disappear_after_read) {
            subtitle(R.string.expiration_type_disappear_after_read_description)
            enabled = false
            contentDescription(R.string.AccessibilityId_disappear_after_read_option)
        },
        radioOption(ExpiryMode.AfterSend(0), R.string.expiration_type_disappear_after_send) {
            subtitle(R.string.expiration_type_disappear_after_send_description)
            enabled = false
            contentDescription(R.string.AccessibilityId_disappear_after_send_option)
        }
    )

    private fun contactRecipientOptions() = listOf(
        radioOption(ExpiryMode.NONE, R.string.expiration_off) {
            contentDescription(R.string.AccessibilityId_disable_disappearing_messages)
        },
        radioOption(ExpiryMode.AfterRead(0), R.string.expiration_type_disappear_after_read) {
            subtitle(R.string.expiration_type_disappear_after_read_description)
            contentDescription(R.string.AccessibilityId_disappear_after_read_option)
        },
        radioOption(ExpiryMode.AfterSend(0), R.string.expiration_type_disappear_after_send) {
            subtitle(R.string.expiration_type_disappear_after_send_description)
            contentDescription(R.string.AccessibilityId_disappear_after_send_option)
        }
    )

    private fun closedGroupRecipientOptions() = listOf(
        radioOption(ExpiryMode.NONE, R.string.expiration_off) {
            contentDescription(R.string.AccessibilityId_disable_disappearing_messages)
        },
        radioOption(ExpiryMode.AfterSend(0), R.string.expiration_type_disappear_after_send) {
            subtitle(R.string.expiration_type_disappear_after_send_description)
            contentDescription(R.string.AccessibilityId_disappear_after_send_option)
        }
    )

    private fun noteToSelfOptions() = listOf(
        radioOption(ExpiryMode.NONE, R.string.expiration_off) {
            contentDescription(R.string.AccessibilityId_disable_disappearing_messages)
        },
        radioOption(ExpiryMode.AfterSend(0), R.string.expiration_type_disappear_after_send) {
            subtitle(R.string.expiration_type_disappear_after_send_description)
            contentDescription(R.string.AccessibilityId_disappear_after_send_option)
        }
    )

    @dagger.assisted.AssistedFactory
    interface AssistedFactory {
        fun create(
            threadId: Long,
            @Assisted("afterRead") afterReadOptions: List<ExpirationRadioOption>,
            @Assisted("afterSend") afterSendOptions: List<ExpirationRadioOption>
        ): Factory
    }

    @Suppress("UNCHECKED_CAST")
    class Factory @AssistedInject constructor(
        @Assisted private val threadId: Long,
        @Assisted("afterRead") private val afterReadOptions: List<ExpirationRadioOption>,
        @Assisted("afterSend") private val afterSendOptions: List<ExpirationRadioOption>,
        private val textSecurePreferences: TextSecurePreferences,
        private val messageExpirationManager: MessageExpirationManagerProtocol,
        private val threadDb: ThreadDatabase,
        private val groupDb: GroupDatabase,
        private val storage: Storage
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ExpirationSettingsViewModel(
                threadId,
                afterReadOptions,
                afterSendOptions,
                textSecurePreferences,
                messageExpirationManager,
                threadDb,
                groupDb,
                storage
            ) as T
        }
    }
}

data class ExpirationSettingsUiState(
    val isSelfAdmin: Boolean = false,
    val showExpirationTypeSelector: Boolean = false,
    val settingsSaved: Boolean? = null,
    val recipient: Recipient? = null
)
