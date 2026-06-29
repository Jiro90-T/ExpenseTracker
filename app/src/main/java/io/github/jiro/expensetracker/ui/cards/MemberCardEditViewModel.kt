package io.github.jiro.expensetracker.ui.cards

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stable identifier for a name-field validation failure.
 *
 * - [REQUIRED] — name is blank after trim.
 * - [DUPLICATE] — placeholder for the Phase B uniqueness check; the field
 *   already exists on the form so the UI can render a tailored message
 *   without re-deriving the error class.
 */
sealed interface NameError {
    object REQUIRED : NameError
    data class DUPLICATE(val existingId: Long) : NameError
}

/** Stable identifier for an image-state failure surfaced to the UI. */
sealed interface ImageError {
    /** No image attached (required by the edit form). */
    object REQUIRED : ImageError
    /** The attached image could not be decoded at save time. */
    object LOAD_FAILED : ImageError
}

/**
 * Form state for [MemberCardEditScreen].
 *
 * The screen renders a single text field for each value; the date, color,
 * and icon are simple selections rather than nested dialogs. The state
 * carries enough metadata to support:
 *  - the bottom-bar Save enable logic (`name` non-blank AND `imageUri` set),
 *  - the discard-changes prompt (`isDirty` vs. baseline),
 *  - the save spinner (`isSaving`),
 *  - the success-side-effect that pops the screen (`saveComplete`),
 *  - the inline error chips (`nameError`, `imageError`).
 */
data class MemberCardEditUiState(
    val isEdit: Boolean = false,
    val name: String = "",
    /**
     * The current image URI as a string. `content://...` for gallery picks,
     * `file://...` for camera captures, and the absolute file path for
     * existing cards whose image is stored internally. The image picker
     * writes the URI for new picks; load hydration writes the absolute path
     * so the [MemberCardImage] composable can render it via the repository.
     */
    val imageUri: String? = null,
    val memberIdText: String = "",
    val colorHex: Int? = null,
    val icon: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val notes: String = "",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val nameError: NameError? = null,
    val imageError: ImageError? = null,
    val errorMessage: String? = null,
    /** Snapshot of the form taken at hydration; null until hydration finishes. */
    val baseline: MemberCardForm? = null,
    /**
     * Initial image path (the relative path under `<filesDir>/cards/`) used
     * to compare against new picks for dirty detection. Null for new cards.
     */
    val initialImagePath: String? = null,
    /** True if any field — including the image — differs from the baseline. */
    val isDirty: Boolean = false,
)

/**
 * Edit VM for the member-cards feature. Handles both the Add screen (no
 * `cardId` arg) and the Edit screen (`cardId` arg). The route argument
 * key is wired in Task 13's AppNav changes — keep this in sync.
 *
 * The repository is exposed as `val` (not `private val`) so the screen can
 * hand it to [MemberCardImage] for decoding the picked/existing photo,
 * mirroring the pattern Task 10/11 set on the list/detail VMs.
 */
@HiltViewModel
class MemberCardEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val repository: MemberCardRepository,
) : ViewModel() {

    private val cardId: Long? = savedStateHandle.get<Long>("cardId")?.takeIf { it > 0L }

    private val _state = MutableStateFlow(MemberCardEditUiState(isEdit = cardId != null))
    val state: StateFlow<MemberCardEditUiState> = _state.asStateFlow()

    init {
        if (cardId != null) {
            loadExisting(cardId)
        } else {
            // For new cards, set the baseline to the empty initial state so
            // dirty detection has something to compare against from the
            // moment the screen renders.
            _state.update {
                val loaded = it.copy(isLoaded = true, baseline = it.toForm())
                loaded.copy(isDirty = isDirty(loaded.baseline, loaded))
            }
        }
    }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            val card = withContext(Dispatchers.IO) { repository.getById(id) }
            if (card == null) {
                _state.update {
                    it.copy(isLoaded = true, errorMessage = "Card not found")
                }
                return@launch
            }
            // Resolve the absolute path of the stored image so the image
            // composable can decode it directly without a path round-trip.
            val absoluteImagePath = withContext(Dispatchers.IO) {
                repository.absolutePath(card.imagePath)?.absolutePath
            }
            _state.update {
                val baselineForm = MemberCardForm(
                    name = card.name,
                    memberIdText = card.memberIdText,
                    colorHex = card.colorHex,
                    icon = card.icon,
                    expiresAtEpochMillis = card.expiresAtEpochMillis,
                    notes = card.notes,
                )
                val hydrated = it.copy(
                    name = card.name,
                    imageUri = absoluteImagePath,
                    memberIdText = card.memberIdText.orEmpty(),
                    colorHex = card.colorHex,
                    icon = card.icon,
                    expiresAtEpochMillis = card.expiresAtEpochMillis,
                    notes = card.notes.orEmpty(),
                    isLoaded = true,
                    initialImagePath = card.imagePath,
                    baseline = baselineForm,
                )
                hydrated.copy(isDirty = isDirty(hydrated.baseline, hydrated))
            }
        }
    }

    fun onNameChange(value: String) {
        _state.update {
            val updated = it.copy(name = value, nameError = null)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    fun onMemberIdChange(value: String) {
        _state.update {
            val updated = it.copy(memberIdText = value)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    fun onColorChange(value: Int?) {
        _state.update {
            val updated = it.copy(colorHex = value)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    fun onIconChange(value: String?) {
        _state.update {
            val updated = it.copy(icon = value)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    fun onExpiresChange(value: Long?) {
        _state.update {
            val updated = it.copy(expiresAtEpochMillis = value)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    fun onNotesChange(value: String) {
        _state.update {
            val updated = it.copy(notes = value)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    /**
     * Apply a freshly picked image URI. Called by the screen after the
     * camera or gallery launcher returns.
     */
    fun onImagePicked(uri: Uri) {
        _state.update {
            val updated = it.copy(imageUri = uri.toString(), imageError = null)
            updated.copy(isDirty = isDirty(it.baseline, updated))
        }
    }

    /**
     * Persist the current form to the repository. Validates required
     * fields first (name, image), then either inserts (Add) or updates
     * (Edit). On Edit, passes `null` for the new image URI if the user
     * did not replace the photo — the repository keeps the existing file
     * intact in that case.
     */
    fun save() {
        val s = _state.value
        if (!s.isLoaded || s.isSaving) return

        // Validate.
        val trimmedName = s.name.trim()
        var hasError = false
        if (trimmedName.isEmpty()) {
            _state.update { it.copy(nameError = NameError.REQUIRED) }
            hasError = true
        }
        if (s.imageUri == null) {
            _state.update { it.copy(imageError = ImageError.REQUIRED) }
            hasError = true
        }
        if (hasError) return

        _state.update { it.copy(isSaving = true) }

        val form = MemberCardForm(
            name = trimmedName,
            memberIdText = s.memberIdText.trim().takeIf { it.isNotEmpty() },
            colorHex = s.colorHex,
            icon = s.icon,
            expiresAtEpochMillis = s.expiresAtEpochMillis,
            notes = s.notes.trim().takeIf { it.isNotEmpty() },
        )

        viewModelScope.launch {
            runCatching {
                if (cardId != null) {
                    // Existing card: only pass newImageUri if the image
                    // actually changed. The state holds the absolute path
                    // for unchanged images and a fresh URI for picks.
                    val newUri = s.imageUri?.let { Uri.parse(it) }
                        ?.takeIf { uri ->
                            val originalPath = s.initialImagePath
                            // No previous image (shouldn't happen for Edit,
                            // but be defensive) or the URI differs from the
                            // resolved original — treat as replaced.
                            if (originalPath == null) return@takeIf true
                            val originalAbsolute =
                                repository.absolutePath(originalPath)?.absolutePath
                            originalAbsolute == null || uri.toString() != originalAbsolute
                        }
                    repository.update(cardId, form, newUri)
                } else {
                    repository.add(Uri.parse(s.imageUri!!), form)
                }
            }.onSuccess {
                _state.update { it.copy(isSaving = false, saveComplete = true) }
            }.onFailure { e ->
                _state.update {
                    it.copy(isSaving = false, errorMessage = e.message ?: "Save failed")
                }
            }
        }
    }

    /** Acknowledge a transient error message (e.g. snackbar shown). */
    fun onErrorShown() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Project the current UI state onto the persistence-side form. */
    private fun MemberCardEditUiState.toForm(): MemberCardForm = MemberCardForm(
        name = name,
        memberIdText = memberIdText.takeIf { it.isNotEmpty() },
        colorHex = colorHex,
        icon = icon,
        expiresAtEpochMillis = expiresAtEpochMillis,
        notes = notes.takeIf { it.isNotEmpty() },
    )

    /**
     * Compare the current state against the baseline. Treats any non-null
     * image on a card whose baseline has no prior image as dirty. Existing
     * cards also become dirty if their `imageUri` no longer matches the
     * resolved absolute path of the original image file.
     */
    private fun isDirty(baseline: MemberCardForm?, current: MemberCardEditUiState): Boolean {
        if (baseline == null) return false // not loaded yet
        val currentForm = current.toForm()
        if (!baseline.contentEquals(currentForm)) return true
        // Compare image state.
        val originalPath = current.initialImagePath
        return if (originalPath == null) {
            // New card: any image attached counts as dirty.
            current.imageUri != null
        } else {
            // Existing card: dirty if the URI no longer references the
            // original file (e.g. user picked a new photo).
            val uri = current.imageUri ?: return false
            val originalAbsolute = repository.absolutePath(originalPath)?.absolutePath
            originalAbsolute == null || uri != originalAbsolute
        }
    }
}